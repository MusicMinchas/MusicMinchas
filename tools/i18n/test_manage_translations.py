#!/usr/bin/env python3
import unittest
import tempfile
from pathlib import Path
import xml.etree.ElementTree as ET

import manage_translations as mt

class TestTranslationManager(unittest.TestCase):
    def test_escaping(self):
        # Plain text with single quote
        self.assertEqual(mt.escape_android_string("Don't stop"), r"Don\'t stop")
        # Unescape back
        self.assertEqual(mt.unescape_android_string(r"Don\'t stop"), "Don't stop")
        # Escaping quotes
        self.assertEqual(mt.escape_android_string('He said "hello"'), r'He said \"hello\"')
        self.assertEqual(mt.unescape_android_string(r'He said \"hello\"'), 'He said "hello"')
        # Preserve format specifiers
        self.assertEqual(mt.escape_android_string("Track %1$s of %2$d"), "Track %1$s of %2$d")

    def test_import_modifications_and_deletions(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            tmp = Path(tmpdir)
            review_file = tmp / "translations_review.xml"
            
            # Setup custom RES directories for test
            orig_en = mt.VALUES_EN
            orig_es = mt.VALUES_ES
            mt.VALUES_EN = tmp / "values"
            mt.VALUES_ES = tmp / "values-es"

            try:
                xml_content = """<?xml version="1.0" encoding="utf-8"?>
<translations>
    <section name="General">
        <string name="keep_both">
            <en>Hello World</en>
            <es>Hola Mundo</es>
        </string>
        <string name="modify_en">
            <en>Updated English Source</en>
            <es>Español</es>
        </string>
        <string name="delete_both" delete="true">
            <en>Old String</en>
            <es>Vieja Cadena</es>
        </string>
        <string name="delete_only_es" delete="es">
            <en>English Only</en>
            <es>Borrar en español</es>
        </string>
        <string name="delete_only_en" delete_en="true">
            <en>Delete in English</en>
            <es>Conservar en español</es>
        </string>
    </section>
    <section name="Plurals">
        <plurals name="songs_count">
            <en>
                <item quantity="one">%d song</item>
                <item quantity="other">%d songs</item>
            </en>
            <es>
                <item quantity="one">%d canción</item>
                <item quantity="other">%d canciones</item>
            </es>
        </plurals>
    </section>
</translations>"""
                review_file.write_text(xml_content, encoding="utf-8")
                
                # Run import
                mt.import_translations(review_file)

                # Check EN strings
                en_strings = mt.read_android_strings(mt.VALUES_EN / "strings.xml")
                self.assertIn("keep_both", en_strings)
                self.assertEqual(en_strings["keep_both"]["text"], "Hello World")
                self.assertEqual(en_strings["modify_en"]["text"], "Updated English Source")
                self.assertNotIn("delete_both", en_strings)
                self.assertIn("delete_only_es", en_strings)
                self.assertNotIn("delete_only_en", en_strings)

                # Check ES strings
                es_strings = mt.read_android_strings(mt.VALUES_ES / "strings.xml")
                self.assertIn("keep_both", es_strings)
                self.assertEqual(es_strings["keep_both"]["text"], "Hola Mundo")
                self.assertNotIn("delete_both", es_strings)
                self.assertNotIn("delete_only_es", es_strings)
                self.assertIn("delete_only_en", es_strings)

                # Check plurals
                en_plurals = mt.read_android_strings(mt.VALUES_EN / "plurals.xml")
                es_plurals = mt.read_android_strings(mt.VALUES_ES / "plurals.xml")
                self.assertIn("songs_count", en_plurals)
                self.assertEqual(en_plurals["songs_count"]["items"]["one"], "%d song")
                self.assertEqual(en_plurals["songs_count"]["items"]["other"], "%d songs")
                self.assertEqual(es_plurals["songs_count"]["items"]["one"], "%d canción")
                self.assertEqual(es_plurals["songs_count"]["items"]["other"], "%d canciones")

                # Test re-export
                exported_review = tmp / "exported_review.xml"
                mt.export_translations(exported_review)
                self.assertTrue(exported_review.exists())

            finally:
                mt.VALUES_EN = orig_en
                mt.VALUES_ES = orig_es

if __name__ == "__main__":
    unittest.main()
