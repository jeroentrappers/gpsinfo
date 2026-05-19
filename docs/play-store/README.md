# Play Store listing copy

One folder per locale. Each contains three files matching Google Play's
[store-listing fields](https://support.google.com/googleplay/android-developer/answer/9866151):

| File | Play Store field | Max length |
|---|---|---|
| `short.txt` | "Short description" | 80 chars |
| `full.txt` | "Full description" | 4000 chars |
| `release-notes.txt` | "What's new in this version" | 500 chars |

The app title is `GPSinfo` everywhere; it does not localise.

## Locales

| Folder | Play Store language code | Android resource bucket |
|---|---|---|
| `en/` | English | `values/` |
| `cs/` | Czech | `values-cs/` |
| `de/` | German | `values-de/` |
| `es/` | Spanish | `values-es/` |
| `fr/` | French | `values-fr/` |
| `it/` | Italian | `values-it/` |
| `ja/` | Japanese | `values-ja/` |
| `nl/` | Dutch | `values-nl/` |
| `pl/` | Polish | `values-pl/` |
| `pt-BR/` | Portuguese (Brazil) | `values-pt-rBR/` |
| `ru/` | Russian | `values-ru/` |
| `tr/` | Turkish | `values-tr/` |

## Translations status

All 11 non-English locales have been passed through a native-speaker
review pass (2026-05-19) — see the commit log for per-locale revision
summaries. Terminology, register, idiom and Play-Store-store-listing
conventions are aligned across all 33 files.

The in-app `strings.xml` files are a separate body of text and **still
carry their `TODO_REVIEW: machine-translated` headers** — they have not
yet had the same treatment.

Section headings written with the U+2590 RIGHT HALF BLOCK glyph (`▌`) — Play
Store renders this as a vertical bar; it survives non-Latin scripts cleanly.
