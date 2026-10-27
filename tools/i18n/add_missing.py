import sys

new_strings_en = {
    'replay_insights': 'Insights',
    'replay_show_top': 'Show top %d',
    'replay_show_all': 'Show all %d',
    'replay_stat_listened': 'listened',
    'replay_play_top': 'Play top %d',
    'replay_shuffle_top': 'Shuffle top songs',
    'replay_save_as_playlist': 'Save as playlist',
    'replay_plays_per_month': 'Plays per month',
    'replay_whole_year': 'Whole %d',
    'replay_empty_plays': "No plays counted in %s yet. A song counts once you've listened to half of it.",
    'replay_insight_new_to_you': 'New to you',
    'replay_insight_peak_hour': 'Peak hour',
    'replay_insight_on_repeat': 'On repeat',
    'replay_insight_on_repeat_desc': '%1$s · %2$d× on %3$s',
    'replay_insight_top_genre': 'Top genre',
    'replay_comparison': '%1$s%2$d%% plays vs %3$s',
    'home_empty_replay_subtitle': 'Your most played',
    'home_recently_added_albums': 'Recently added albums',
    'home_empty_added_subtitle': 'Nothing added yet',
    'home_trending_deezer_subtitle': 'Most popular playlists worldwide',
    'home_top_charts_deezer': 'Top Charts',
    'home_top_charts_deezer_subtitle': 'Top 100 by country & worldwide',
    'catalog_genre_rock': 'Rock & Alternative',
    'catalog_genre_rock_sub': 'Guitar anthems, essentials & classic hits',
    'catalog_genre_metal': 'Metal & Heavy Rock',
    'catalog_genre_metal_sub': 'Heavy riffs, headbangers & dark anthems',
    'catalog_genre_alternative': 'Alternative & Indie',
    'catalog_genre_alternative_sub': 'Indie rock, post-punk & modern alternative',
    'catalog_genre_classical': 'Classical & Piano',
    'catalog_genre_classical_sub': 'Peaceful piano, orchestral & classical',
    'catalog_genre_acoustic': 'Folk & Acoustic',
    'catalog_genre_acoustic_sub': 'Cozy acoustic, coffeehouse & folk',
    'catalog_genre_pop': 'Pop Hits & Anthems',
    'catalog_genre_pop_sub': 'Catchy melodies & modern favorites',
    'catalog_genre_electronic': 'Electronic & Dance',
    'catalog_genre_electronic_sub': 'Beats, synth, techno & club favorites',
    'catalog_genre_jazz': 'Jazz & Lounge',
    'catalog_genre_jazz_sub': 'Smooth jazz, swing & modern brass',
    'catalog_genre_blues': 'Blues & Roots',
    'catalog_genre_blues_sub': 'Soulful blues, slide guitar & delta roots',
    'catalog_genre_rnb': 'R&B & Soul',
    'catalog_genre_rnb_sub': 'Grooves, neo-soul & timeless voices',
    'catalog_genre_rap': 'Hip-Hop & Urban',
    'catalog_genre_rap_sub': 'Beats, bars & street anthems',
    'catalog_genre_soundtracks': 'Soundtracks & Scores',
    'catalog_genre_soundtracks_sub': 'Cinematic orchestral themes & scores',
    'catalog_mood_focus': 'Focus & Study',
    'catalog_mood_focus_sub': 'Lofi, ambient & music for concentration',
    'catalog_mood_workout': 'Workout & Gym',
    'catalog_mood_workout_sub': 'Cardio, running & high-energy beats',
    'catalog_mood_chill': 'Chill & Relax',
    'catalog_mood_chill_sub': 'Cozy acoustic, downtempo & evening chill',
    'catalog_mood_party': 'Party & Good Vibes',
    'catalog_mood_party_sub': 'Upbeat anthems, dancefloor & celebration',
    'catalog_mood_sleep': 'Sleep & Meditation',
    'catalog_mood_sleep_sub': 'Ambient soundscapes, gentle piano & deep rest',
    'catalog_mood_morning': 'Morning & Energy',
    'catalog_mood_morning_sub': 'Sunny acoustic, coffeehouse & morning boost',
    'sources_saf_subtitle_pattern': '%d songs. Only the folders listed here are scanned, including their sub-folders (internal storage, SD card, USB).',
    'sources_saf_add_folder': 'Add folder',
    'sources_drive_reconnect_desc': 'Google asks you to sign in again before Drive songs can play. Your library is kept.',
    'sources_drive_disconnect_confirm': 'Disconnect Google Drive?',
    'sources_drive_disconnect_dialog_message': "The %s will be removed from the library. Plays, favourites and playlists are kept and come back if you connect the same Drive folders again. To refresh Google's access instead, use Reconnect.",
    'sources_lastfm_open_again': 'Open Last.fm again',
    'sources_lastfm_connect': 'Connect Last.fm',
    'sources_catalog_title': 'Deezer & Apple Music',
    'sources_catalog_subtitle_enabled': 'Enabled · Public catalog search, Deezer playlist import & artist discographies (no account needed)',
    'sources_catalog_subtitle_disabled': 'Browse and search the public Deezer & Apple Music catalogs alongside your own library (no account needed)',
    'sources_catalog_enable_toggle': 'Enable Deezer & Apple Music catalog',
    'sources_catalog_enrich_home': 'Enrich Home with Deezer',
    'sources_catalog_enrich_home_desc': 'Show trending charts and curated playlists on the Home tab',
    'sources_catalog_explanation_what': "What both contribute:\n• Catalog search: find artists, albums and songs across Deezer & Apple Music, matched against your Local, Google Drive and Navidrome library.\n• Public playlists (Deezer): search curated and user playlists on Deezer and import them into Playlists using the songs you own.\n• Artist discography: view an artist's full studio & live discography on their page and spot albums missing from your library.",
    'nebula_card_title': 'Nebula add-on Manifest',
    'nebula_card_subtitle_disconnected': 'Stream lossless FLAC audio via Torbox cache for music not in your library using your Eclipse / Stremio add-on manifest URL',
    'nebula_card_subtitle_connected': '%1$s · Connected · Lossless FLAC',
    'nebula_quality_badge': 'Quality: Lossless FLAC (Torbox cache)',
    'nebula_disconnect_confirm': 'Disconnect Nebula add-on?',
    'nebula_disconnect_message': 'MusicMinchas will stop streaming unowned tracks from Torbox. Your owned library songs are unaffected.',
    'nebula_manifest_url_label': 'Add-on manifest URL',
    'nebula_connect_button': 'Connect Add-on',
    'nebula_connecting': 'Connecting…',
    'playlist_personal_empty': 'Tap + to create a playlist or import from Apple Music / Deezer.',
    'playlist_apple_empty': 'No Apple Music playlists imported yet. Tap + to import an Apple Music playlist.',
    'playlist_deezer_empty': 'No Deezer playlists saved yet. Search for playlists on Deezer or tap + to import.',
}

new_strings_es = {
    'replay_insights': 'Estadísticas y curiosidades',
    'replay_show_top': 'Mostrar las %d mejores',
    'replay_show_all': 'Mostrar todas (%d)',
    'replay_stat_listened': 'escuchado',
    'replay_play_top': 'Reproducir las %d mejores',
    'replay_shuffle_top': 'Mezclar mejores canciones',
    'replay_save_as_playlist': 'Guardar como lista',
    'replay_plays_per_month': 'Reproducciones por mes',
    'replay_whole_year': 'Todo el año %d',
    'replay_empty_plays': 'Aún no hay reproducciones en %s. Una canción cuenta cuando has escuchado al menos la mitad.',
    'replay_insight_new_to_you': 'Descubrimientos',
    'replay_insight_peak_hour': 'Hora punta',
    'replay_insight_on_repeat': 'En bucle',
    'replay_insight_on_repeat_desc': '%1$s · %2$d veces el %3$s',
    'replay_insight_top_genre': 'Género principal',
    'replay_comparison': '%1$s%2$d%% reproducciones vs %3$s',
    'home_empty_replay_subtitle': 'Lo que más has escuchado',
    'home_recently_added_albums': 'Álbumes añadidos recientemente',
    'home_empty_added_subtitle': 'Nada añadido aún',
    'home_trending_deezer_subtitle': 'Listas más populares del mundo',
    'home_top_charts_deezer': 'Listas de éxitos',
    'home_top_charts_deezer_subtitle': 'Top 100 por país y mundial',
    'catalog_genre_rock': 'Rock y alternativo',
    'catalog_genre_rock_sub': 'Himnos de guitarra, clásicos e imprescindibles',
    'catalog_genre_metal': 'Metal y hard rock',
    'catalog_genre_metal_sub': 'Riffs potentes, caña e himnos oscuros',
    'catalog_genre_alternative': 'Alternativo e indie',
    'catalog_genre_alternative_sub': 'Indie rock, post-punk y alternativo moderno',
    'catalog_genre_classical': 'Clásica y piano',
    'catalog_genre_classical_sub': 'Piano relajante, orquestal y música clásica',
    'catalog_genre_acoustic': 'Folk y acústico',
    'catalog_genre_acoustic_sub': 'Acústico acogedor, cantautores y folk',
    'catalog_genre_pop': 'Éxitos de pop',
    'catalog_genre_pop_sub': 'Melodías pegadizas y favoritos actuales',
    'catalog_genre_electronic': 'Electrónica y dance',
    'catalog_genre_electronic_sub': 'Ritmos, sintetizadores, techno y club',
    'catalog_genre_jazz': 'Jazz y lounge',
    'catalog_genre_jazz_sub': 'Smooth jazz, swing e instrumentales',
    'catalog_genre_blues': 'Blues y raíces',
    'catalog_genre_blues_sub': 'Blues clásico, guitarra slide y raíces delta',
    'catalog_genre_rnb': 'R&B y soul',
    'catalog_genre_rnb_sub': 'Grooves, neo-soul y grandes voces',
    'catalog_genre_rap': 'Hip-Hop y música urbana',
    'catalog_genre_rap_sub': 'Beats, rimas e himnos urbanos',
    'catalog_genre_soundtracks': 'Bandas sonoras',
    'catalog_genre_soundtracks_sub': 'Temas orquestales y bandas sonoras de cine',
    'catalog_mood_focus': 'Concentración y estudio',
    'catalog_mood_focus_sub': 'Lofi, ambiental y música para estudiar',
    'catalog_mood_workout': 'Entrenamiento y deporte',
    'catalog_mood_workout_sub': 'Cardio, running y ritmos con energía',
    'catalog_mood_chill': 'Relax y tranquilidad',
    'catalog_mood_chill_sub': 'Acústico suave, downtempo y descanso',
    'catalog_mood_party': 'Fiesta y buen rollo',
    'catalog_mood_party_sub': 'Temas animados, pista de baile y fiesta',
    'catalog_mood_sleep': 'Sueño y meditación',
    'catalog_mood_sleep_sub': 'Paisajes sonoros, piano suave y descanso profundo',
    'catalog_mood_morning': 'Despertar con energía',
    'catalog_mood_morning_sub': 'Acústico alegre, café y energía matutina',
    'sources_saf_subtitle_pattern': '%d canciones. Solo se escanean las carpetas aquí listadas y sus subcarpetas (almacenamiento interno, tarjeta SD, USB).',
    'sources_saf_add_folder': 'Añadir carpeta',
    'sources_drive_reconnect_desc': 'Google solicita iniciar sesión de nuevo para reproducir canciones de Drive. Tu biblioteca se mantendrá intacta.',
    'sources_drive_disconnect_confirm': '¿Desconectar Google Drive?',
    'sources_drive_disconnect_dialog_message': "Se eliminarán %s de la biblioteca. Las reproducciones, favoritos y listas se conservan y volverán a estar disponibles si conectas las mismas carpetas de Drive más adelante. Para refrescar el acceso de Google, usa Reconectar.",
    'sources_lastfm_open_again': 'Abrir Last.fm de nuevo',
    'sources_lastfm_connect': 'Conectar Last.fm',
    'sources_catalog_title': 'Deezer y Apple Music',
    'sources_catalog_subtitle_enabled': 'Activado · Búsqueda en catálogo público, importación de listas de Deezer y discografías de artistas (sin cuenta)',
    'sources_catalog_subtitle_disabled': 'Explora y busca en los catálogos públicos de Deezer y Apple Music junto con tu propia biblioteca (sin cuenta)',
    'sources_catalog_enable_toggle': 'Activar catálogo de Deezer y Apple Music',
    'sources_catalog_enrich_home': 'Enriquecer Inicio con Deezer',
    'sources_catalog_enrich_home_desc': 'Mostrar listas de éxitos y listas recomendadas en la pestaña Inicio',
    'sources_catalog_explanation_what': "Qué aporta cada servicio:\n• Búsqueda en catálogo: encuentra artistas, álbumes y canciones en Deezer y Apple Music, cotejados con tu música local, de Google Drive y Navidrome.\n• Listas públicas (Deezer): busca listas públicas en Deezer e impórtalas a tus Listas usando tus canciones en propiedad.\n• Discografía del artista: consulta la discografía completa de estudio y en directo en su ficha y descubre qué álbumes te faltan.",
    'nebula_card_title': 'Manifiesto del complemento Nebula',
    'nebula_card_subtitle_disconnected': 'Transmite audio FLAC sin pérdidas a través de la caché de Torbox para música no presente en tu biblioteca mediante tu URL de manifiesto de Eclipse o Stremio',
    'nebula_card_subtitle_connected': '%1$s · Conectado · FLAC sin pérdidas',
    'nebula_quality_badge': 'Calidad: FLAC sin pérdidas (caché Torbox)',
    'nebula_disconnect_confirm': '¿Desconectar complemento Nebula?',
    'nebula_disconnect_message': 'MusicMinchas dejará de transmitir pistas no propias desde Torbox. Las canciones de tu biblioteca propia no se verán afectadas.',
    'nebula_manifest_url_label': 'URL del manifiesto del complemento',
    'nebula_connect_button': 'Conectar complemento',
    'nebula_connecting': 'Conectando…',
    'playlist_personal_empty': 'Pulsa + para crear una lista o importar desde Apple Music o Deezer.',
    'playlist_apple_empty': 'Aún no has importado listas de Apple Music. Pulsa + para importar una lista de Apple Music.',
    'playlist_deezer_empty': 'Aún no tienes listas guardadas de Deezer. Busca listas en Deezer o pulsa + para importar.',
}

def escape_xml(s):
    s = s.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')
    s = s.replace('\n', '\\n')
    s = s.replace("'", "\\'")
    return s

def update_xml(path, new_entries):
    with open(path, 'r', encoding='utf-8') as f:
        content = f.read()

    closing = '</resources>'
    idx = content.rfind(closing)
    if idx == -1:
        raise ValueError('No </resources> found')

    existing_part = content[:idx].rstrip()
    additions = []
    for k, v in new_entries.items():
        if f'name="{k}"' in existing_part:
            continue
        escaped_v = escape_xml(v)
        additions.append(f'    <string name="{k}">{escaped_v}</string>')

    if additions:
        new_content = existing_part + '\n' + '\n'.join(additions) + '\n' + closing + '\n'
        with open(path, 'w', encoding='utf-8') as f:
            f.write(new_content)
        print(f'Added {len(additions)} keys to {path}')
    else:
        print(f'No new keys to add to {path}')

update_xml('app/src/main/res/values/strings.xml', new_strings_en)
update_xml('app/src/main/res/values-es/strings.xml', new_strings_es)
