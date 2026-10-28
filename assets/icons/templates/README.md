# Launcher icon templates

Draw three layers, each on the same **432 × 432 px** canvas (108dp at xxxhdpi):

| File to produce | Contents |
|---|---|
| `assets/icons/ic_launcher_background.svg` | Colour or gradient, **edge to edge**. No logo. |
| `assets/icons/ic_launcher_foreground.svg` | The mark only, on transparency, inside the 264 px safe circle. |
| `assets/icons/ic_launcher_monochrome.svg` | Flat single-colour silhouette on transparency. Optional; Android tints it for themed icons. |

## The numbers

- **432 × 432 px** canvas — 108dp at xxxhdpi.
- **72 px margin** on each side (18dp) is reserved for masking and parallax. Nothing meaningful goes there.
- **264 px centred circle** (66dp) is the masked viewport — the only area guaranteed to stay visible.
- A logo should be **192–264 px** across (48–66dp).

"Full bleed" means the background reaches all four edges. The launcher crops the
square to a circle, squircle or rounded square and the app does not get to choose
which, so the corners must be expendable.

Open `icon-reference-sheet.svg` to see all of this drawn out, including which
parts a mask throws away.

---

## Status bar icon — a different beast

`ic_notification.template.svg`, canvas **24 × 24 (dp)**. Produce
`assets/icons/ic_notification.svg`.

This is not the launcher icon shrunk. Android **discards the colour entirely**
and tints the shape, so only the alpha channel survives:

| Rule | Why |
|---|---|
| One flat colour on transparency | The colour is thrown away; only the silhouette is drawn |
| No gradients or shadows | They flatten into a muddy blob |
| No embedded raster images | A vector drawable cannot carry them |
| Artwork inside the central **22 × 22** | Keeps it off the status bar text |
| Strokes **2 or heavier** | Anything finer disappears at 24dp |
| Simplify, do not scale | The launcher mark's ring-and-arrow detail will not read this small |

It lives in `drawable/`, not `mipmap/`, and one vector covers every density.

## Then

```bash
bash ~/bin/run-privileged.sh ~/bin/install-librsvg-privileged.sh   # once, installs rsvg-convert
./scripts/generate-icons.sh                                        # every time the art changes
```

Delete the `GUIDES` group from each template before exporting.
