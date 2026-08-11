# Local demo assets (gitignored)

Drop **real device photos** here for a polished internal demo — they stay on
your machine and are **never committed** (Apple/Samsung images are copyrighted;
fine on your screen, not in a public repo).

## Device photos
Put files in `devices/` named by slug, then run `ops/seed/seed_demo_images.py`:

| Offering | File (any of png/jpg/webp) |
|---|---|
| Samsung Galaxy S26 | `devices/samsung-galaxy-s26.png` |
| Apple iPhone 17 | `devices/iphone-17.png` |
| Apple iPhone 17 Pro | `devices/iphone-17-pro.png` |

A real photo, if present, always wins over the generated tile. Square-ish images
on a plain/transparent background look best. Without them, clean SVG device
tiles are used automatically (safe for the repo).
