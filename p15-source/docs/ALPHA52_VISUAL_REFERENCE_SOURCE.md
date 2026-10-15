# Alpha52 Personal Assistant visual source

The Arabic visual layer is derived from the user-approved file:
`AB_File_Pro_Personal_Assistant_Visual_Implementation_Guide_v1.1.pdf`.

Reference pages used:
- Home: pages 6 and 7.
- Prayer through Health: pages 8 through 23.

Rendering/crop rule used for the phone mockups:
- PDF pages rendered at 2x.
- Primary phone content crop: `(x=274, y=220) -> (x=918, y=1588)` = `644 x 1368`.
- Home continuation (page 7) crop: `(x=274, y=300) -> (x=918, y=1588)` = `644 x 1288`.
- Crops are stored losslessly as WebP in `res/drawable-nodpi`.

This prevents visual guessing: the reference art, colours, spacing and Arabic text are carried directly from the approved guide.
