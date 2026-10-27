#!/usr/bin/env python3
"""
MusicMinchas Translation Synchronization Tool
==============================================
Manages bidirectional synchronization between:
- Android resource files:
    - app/src/main/res/values/strings.xml
    - app/src/main/res/values-es/strings.xml
    - app/src/main/res/values/plurals.xml
    - app/src/main/res/values-es/plurals.xml
- Multilingual review file:
    - translations_review.xml

Commands:
  python tools/i18n/manage_translations.py export
  python tools/i18n/manage_translations.py import
  python tools/i18n/manage_translations.py test
"""

import os
import sys
import re
import argparse
import xml.etree.ElementTree as ET
from xml.dom import minidom
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent.parent.parent
RES_DIR = BASE_DIR / "app" / "src" / "main" / "res"
VALUES_EN = RES_DIR / "values"
VALUES_ES = RES_DIR / "values-es"

REVIEW_FILE_DEFAULT = BASE_DIR / "translations_review.xml"


def unescape_android_string(text: str) -> str:
    """Converts Android XML escaping back to human-readable plain text."""
    if not text:
        return ""
    # Remove surrounding quotes if present
    if (text.startswith('"') and text.endswith('"')) or (text.startswith("'") and text.endswith("'")):
        if len(text) >= 2:
            text = text[1:-1]
    # Replace \' with ' and \" with "
    text = text.replace(r"\'", "'").replace(r'\"', '"')
    # Replace \n with newline and \t with tab
    text = text.replace(r"\n", "\n").replace(r"\t", "\t")
    # Replace @string/ references if needed, or keep as is
    return text


def escape_android_string(text: str) -> str:
    """Escapes plain text according to Android strings.xml specifications."""
    if not text:
        return ""
    # Avoid escaping already escaped sequences
    # Escape quotes
    # Android requires unquoted single quotes to be escaped: ' -> \'
    # If the string contains newlines, preserve \n
    result = []
    i = 0
    length = len(text)
    while i < length:
        ch = text[i]
        if ch == "'":
            if i > 0 and text[i-1] == '\\':
                result.append("'")
            else:
                result.append(r"\'")
        elif ch == '"':
            if i > 0 and text[i-1] == '\\':
                result.append('"')
            else:
                result.append(r'\"')
        elif ch == '&':
            remaining = text[i:]
            if remaining.startswith(("&amp;", "&lt;", "&gt;", "&quot;", "&apos;")):
                result.append("&")
            else:
                result.append("&amp;")
        elif ch == '<':
            result.append("&lt;")
        elif ch == '>':
            result.append("&gt;")
        elif ch == '@' and i == 0:
            # Escape leading '@' so Android doesn't treat it as a resource identifier
            result.append(r"\@")
        elif ch == '?' and i == 0:
            # Escape leading '?' so Android doesn't treat it as a theme attribute
            result.append(r"\?")
        elif ch == '\n':
            result.append(r"\n")
        elif ch == '\r':
            pass
        elif ch == '\t':
            result.append(r"\t")
        else:
            result.append(ch)
        i += 1
    return "".join(result)


def read_android_strings(filepath: Path) -> dict:
    """Reads an Android strings.xml or plurals.xml file and returns a dict of items."""
    if not filepath.exists():
        return {}
    try:
        tree = ET.parse(str(filepath))
        root = tree.getroot()
    except Exception as e:
        print(f"Warning: Failed to parse {filepath}: {e}")
        return {}

    items = {}
    for elem in root:
        if elem.tag == "string":
            name = elem.get("name")
            if not name:
                continue
            # Get full text including inner XML if any (like <b></b>)
            text = (elem.text or "") + "".join(ET.tostring(child, encoding="unicode") for child in elem)
            translatable = elem.get("translatable", "true").lower() != "false"
            items[name] = {
                "type": "string",
                "name": name,
                "text": unescape_android_string(text.strip()),
                "translatable": translatable,
            }
        elif elem.tag == "plurals":
            name = elem.get("name")
            if not name:
                continue
            plural_items = {}
            for child in elem:
                if child.tag == "item":
                    q = child.get("quantity")
                    text = (child.text or "") + "".join(ET.tostring(c, encoding="unicode") for c in child)
                    plural_items[q] = unescape_android_string(text.strip())
            items[name] = {
                "type": "plurals",
                "name": name,
                "items": plural_items,
            }
    return items


def infer_section(name: str) -> str:
    """Infers an intuitive section name from the string key prefix."""
    if name.startswith("tab_") or name.startswith("nav_"):
        return "Navigation & Tabs"
    if name.startswith("search_"):
        return "Search & Filters"
    if name.startswith("home_") or name.startswith("mix_") or name in ("recently_played", "recently_added", "heavy_rotation", "most_played", "favourites", "made_for_you", "trending_on_deezer", "top_charts"):
        return "Home & Recommendations"
    if name.startswith("player_") or name.startswith("queue_") or name.startswith("sonos_") or name.startswith("lyrics_") or name.startswith("cast_"):
        return "Player & Queue"
    if name.startswith("playlist_"):
        return "Playlists"
    if name.startswith("replay_"):
        return "Replay"
    if name.startswith("discover_") or name.startswith("concert_") or name.startswith("country_"):
        return "Discover"
    if name.startswith("action_") or name.startswith("dialog_") or name.startswith("menu_") or name in ("cancel", "confirm", "delete", "rename", "create", "save", "done", "close", "ok"):
        return "Actions & Dialogs"
    if name.startswith("source_") or name.startswith("sources_") or name.startswith("sync_") or name.startswith("settings_") or name.startswith("drive_") or name.startswith("server_") or name.startswith("download_"):
        return "Sources & Settings"
    if name.startswith("notif_") or name.startswith("service_") or name.startswith("channel_"):
        return "Notifications & Services"
    return "General"


def export_translations(review_file: Path):
    """Exports Android strings and plurals into the multilingual review file."""
    en_strings = read_android_strings(VALUES_EN / "strings.xml")
    es_strings = read_android_strings(VALUES_ES / "strings.xml")
    en_plurals = read_android_strings(VALUES_EN / "plurals.xml")
    es_plurals = read_android_strings(VALUES_ES / "plurals.xml")

    # Merge strings
    all_string_names = list(en_strings.keys())
    for k in es_strings.keys():
        if k not in all_string_names:
            all_string_names.append(k)

    # Merge plurals
    all_plural_names = list(en_plurals.keys())
    for k in es_plurals.keys():
        if k not in all_plural_names:
            all_plural_names.append(k)

    # If review file already exists, preserve its section structure & existing comments/contexts
    existing_sections = {}
    item_section_map = {}
    item_context_map = {}

    if review_file.exists():
        try:
            tree = ET.parse(str(review_file))
            root = tree.getroot()
            for sec_node in root.findall("section"):
                sec_name = sec_node.get("name", "General")
                existing_sections[sec_name] = []
                for item_node in sec_node:
                    name = item_node.get("name")
                    if name:
                        item_section_map[name] = sec_name
                        ctx = item_node.get("context")
                        if ctx:
                            item_context_map[name] = ctx
            # Direct children without section
            for item_node in root:
                if item_node.tag in ("string", "plurals"):
                    name = item_node.get("name")
                    if name and name not in item_section_map:
                        item_section_map[name] = infer_section(name)
                        ctx = item_node.get("context")
                        if ctx:
                            item_context_map[name] = ctx
        except Exception as e:
            print(f"Warning: Could not parse existing {review_file} for section preservation: {e}")

    # Build XML tree
    root = ET.Element("translations")
    
    # Group items by section
    sections = {}
    for name in all_string_names:
        sec = item_section_map.get(name, infer_section(name))
        sections.setdefault(sec, []).append(("string", name))

    for name in all_plural_names:
        sec = item_section_map.get(name, "Plurals")
        sections.setdefault(sec, []).append(("plurals", name))

    # Maintain existing section order if known
    ordered_section_names = []
    for s in existing_sections.keys():
        if s in sections and s not in ordered_section_names:
            ordered_section_names.append(s)
    for s in sections.keys():
        if s not in ordered_section_names:
            ordered_section_names.append(s)

    for sec_name in ordered_section_names:
        sec_elem = ET.SubElement(root, "section", attrib={"name": sec_name})
        for item_type, name in sections[sec_name]:
            if item_type == "string":
                en_val = en_strings.get(name, {}).get("text", "")
                es_val = es_strings.get(name, {}).get("text", "")
                attribs = {"name": name}
                if name in item_context_map:
                    attribs["context"] = item_context_map[name]
                str_elem = ET.SubElement(sec_elem, "string", attrib=attribs)
                en_elem = ET.SubElement(str_elem, "en")
                en_elem.text = en_val
                es_elem = ET.SubElement(str_elem, "es")
                es_elem.text = es_val
            elif item_type == "plurals":
                en_items = en_plurals.get(name, {}).get("items", {})
                es_items = es_plurals.get(name, {}).get("items", {})
                attribs = {"name": name}
                if name in item_context_map:
                    attribs["context"] = item_context_map[name]
                pl_elem = ET.SubElement(sec_elem, "plurals", attrib=attribs)
                
                en_elem = ET.SubElement(pl_elem, "en")
                for q, t in en_items.items():
                    item = ET.SubElement(en_elem, "item", attrib={"quantity": q})
                    item.text = t
                    
                es_elem = ET.SubElement(pl_elem, "es")
                for q, t in es_items.items():
                    item = ET.SubElement(es_elem, "item", attrib={"quantity": q})
                    item.text = t

    # Format pretty XML
    xml_str = ET.tostring(root, encoding="utf-8")
    dom = minidom.parseString(xml_str)
    pretty_xml = dom.toprettyxml(indent="    ", encoding="utf-8").decode("utf-8")

    # Add header comment
    header = """<!--
  MusicMinchas Multilingual Translation Review File
  ==================================================
  Instructions:
  1. Review and edit the Spanish translations inside <es>...</es>.
  2. You can modify the source English text inside <en>...</en> to update strings.xml.
  3. To delete a string or plural:
     - From both English and Spanish: add delete="true" (or delete="both")
     - From English only: add delete="en" (or delete_en="true")
     - From Spanish only: add delete="es" (or delete_es="true")
  4. Run `python tools/i18n/manage_translations.py import` to sync your changes back to Android XMLs.
-->\n"""
    # Replace default declaration with declaration + header
    pretty_xml = re.sub(r'<\?xml[^>]+\?>', '<?xml version="1.0" encoding="utf-8"?>\n' + header, pretty_xml, count=1)
    # Remove extra blank lines generated by minidom
    pretty_xml = re.sub(r'\n\s*\n', '\n', pretty_xml)

    review_file.write_text(pretty_xml, encoding="utf-8")
    print(f"Exported {len(all_string_names)} strings and {len(all_plural_names)} plurals to {review_file}")


def import_translations(review_file: Path):
    """Imports translations from the review file back into Android strings.xml and plurals.xml."""
    if not review_file.exists():
        print(f"Error: Review file {review_file} does not exist.")
        sys.exit(1)

    tree = ET.parse(str(review_file))
    root = tree.getroot()

    # Data structures to accumulate Android items
    en_strings = {}
    es_strings = {}
    en_plurals = {}
    es_plurals = {}

    deleted_count = 0
    updated_en_count = 0
    updated_es_count = 0

    # Search all string and plurals elements (direct children or within section)
    all_items = root.findall(".//string") + root.findall(".//plurals")

    for elem in all_items:
        name = elem.get("name")
        if not name:
            continue

        del_attr = elem.get("delete", "").strip().lower()
        del_en_attr = elem.get("delete_en", "").strip().lower() == "true"
        del_es_attr = elem.get("delete_es", "").strip().lower() == "true"

        delete_en = del_en_attr or del_attr in ("true", "both", "all", "en")
        delete_es = del_es_attr or del_attr in ("true", "both", "all", "es")

        if delete_en and delete_es:
            deleted_count += 1
            continue

        if elem.tag == "string":
            en_node = elem.find("en")
            es_node = elem.find("es")

            en_text = (en_node.text or "").strip() if en_node is not None else ""
            es_text = (es_node.text or "").strip() if es_node is not None else ""

            if not delete_en and en_text:
                en_strings[name] = escape_android_string(en_text)
                updated_en_count += 1

            if not delete_es and es_text:
                es_strings[name] = escape_android_string(es_text)
                updated_es_count += 1

        elif elem.tag == "plurals":
            en_node = elem.find("en")
            es_node = elem.find("es")

            if not delete_en and en_node is not None:
                plural_map = {}
                for item in en_node.findall("item"):
                    q = item.get("quantity")
                    t = (item.text or "").strip()
                    if q and t:
                        plural_map[q] = escape_android_string(t)
                if plural_map:
                    en_plurals[name] = plural_map
                    updated_en_count += 1

            if not delete_es and es_node is not None:
                plural_map = {}
                for item in es_node.findall("item"):
                    q = item.get("quantity")
                    t = (item.text or "").strip()
                    if q and t:
                        plural_map[q] = escape_android_string(t)
                if plural_map:
                    es_plurals[name] = plural_map
                    updated_es_count += 1

    # Write output Android XMLs
    write_android_strings(VALUES_EN / "strings.xml", en_strings)
    write_android_strings(VALUES_ES / "strings.xml", es_strings)
    
    if en_plurals:
        write_android_plurals(VALUES_EN / "plurals.xml", en_plurals)
    if es_plurals:
        write_android_plurals(VALUES_ES / "plurals.xml", es_plurals)

    print(f"Import completed successfully:")
    print(f"  - English strings written: {len(en_strings)}")
    print(f"  - Spanish strings written: {len(es_strings)}")
    print(f"  - English plurals written: {len(en_plurals)}")
    print(f"  - Spanish plurals written: {len(es_plurals)}")
    if deleted_count > 0:
        print(f"  - Items marked for deletion: {deleted_count}")


def write_android_strings(filepath: Path, strings: dict):
    """Writes a dictionary of name -> escaped_text into an Android strings.xml file."""
    filepath.parent.mkdir(parents=True, exist_ok=True)

    lines = ['<?xml version="1.0" encoding="utf-8"?>', "<resources>"]
    # App name should always be present
    if "app_name" not in strings:
        strings["app_name"] = "MusicMinchas"

    for name, text in sorted(strings.items()):
        lines.append(f'    <string name="{name}">{text}</string>')
    lines.append("</resources>\n")

    filepath.write_text("\n".join(lines), encoding="utf-8")


def write_android_plurals(filepath: Path, plurals: dict):
    """Writes a dictionary of name -> {quantity: escaped_text} into an Android plurals.xml file."""
    filepath.parent.mkdir(parents=True, exist_ok=True)

    lines = ['<?xml version="1.0" encoding="utf-8"?>', "<resources>"]
    for name, items in sorted(plurals.items()):
        lines.append(f'    <plurals name="{name}">')
        for q, text in sorted(items.items()):
            lines.append(f'        <item quantity="{q}">{text}</item>')
        lines.append("    </plurals>")
    lines.append("</resources>\n")

    filepath.write_text("\n".join(lines), encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description="MusicMinchas Translation Tool")
    subparsers = parser.add_subparsers(dest="command", required=True)

    export_parser = subparsers.add_parser("export", help="Export Android XML resources to translations_review.xml")
    export_parser.add_argument("--file", type=Path, default=REVIEW_FILE_DEFAULT, help="Target review XML file")

    import_parser = subparsers.add_parser("import", help="Import translations_review.xml back into Android XML resources")
    import_parser.add_argument("--file", type=Path, default=REVIEW_FILE_DEFAULT, help="Source review XML file")

    args = parser.parse_args()

    if args.command == "export":
        export_translations(args.file)
    elif args.command == "import":
        import_translations(args.file)


if __name__ == "__main__":
    main()
