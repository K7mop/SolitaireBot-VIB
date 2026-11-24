#!/usr/bin/env python3
"""Rebuild the local raster-template asset. Requires Pillow and the listed open fonts.
No game screenshots or user-labelled sample board are embedded.
"""
from pathlib import Path
import base64, json, math
from PIL import Image, ImageDraw, ImageFont

ROOT=Path(__file__).resolve().parents[2]
FONTS=[
('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf','DejaVu Sans'),
('/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf','DejaVu Sans Bold'),
('/usr/share/fonts/truetype/dejavu/DejaVuSerif.ttf','DejaVu Serif'),
('/usr/share/fonts/truetype/dejavu/DejaVuSerif-Bold.ttf','DejaVu Serif Bold'),
('/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf','Liberation Sans'),
('/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf','Liberation Sans Bold'),
('/usr/share/fonts/truetype/liberation/LiberationSerif-Regular.ttf','Liberation Serif'),
('/usr/share/fonts/truetype/liberation/LiberationSerif-Bold.ttf','Liberation Serif Bold'),
('/usr/share/fonts/truetype/noto/NotoSans-Regular.ttf','Noto Sans'),
('/usr/share/fonts/truetype/noto/NotoSans-Bold.ttf','Noto Sans Bold'),
('/usr/share/fonts/truetype/noto/NotoSerif-Regular.ttf','Noto Serif'),
('/usr/share/fonts/truetype/noto/NotoSerif-Bold.ttf','Noto Serif Bold'),
('/usr/share/fonts/truetype/open-sans/OpenSans-Regular.ttf','Open Sans'),
('/usr/share/fonts/truetype/open-sans/OpenSans-Bold.ttf','Open Sans Bold')]
RANKS=[('R',c,c) for c in 'A23456789JQK']+[('R','T','10'),('R','T','T')]
SUITS=[('S',l,c) for l,c in zip('CDHS','♣♦♥♠')]+[('S',l,c) for l,c in zip('CDHS','♧♢♡♤')]
RECYCLE=[('U','RECYCLE',c) for c in '↻↺⟳⟲']
DISTRACTORS=[('X','?'+c,c) for c in 'O01ILBX?ZS']+[('Y','?'+str(i),c) for i,c in enumerate('●■▲▼★+')]
SIZES=[12,14,16,18,20,22,24,26,28,30,34,38,44,52,64]

def norm(image,threshold):
    mask=image.point(lambda v: 255 if v<threshold else 0)
    bounds=mask.getbbox()
    if bounds is None:return None
    glyph=mask.crop(bounds)
    w,h=glyph.size
    ratio=min(20/w,20/h)
    sw=max(1,int(w*ratio+.5));sh=max(1,int(h*ratio+.5))
    px=glyph.load(); out=bytearray(72)
    for y in range(sh):
        for x in range(sw):
            if px[x*w//sw,y*h//sh]:
                idx=((24-sh)//2+y)*24+(24-sw)//2+x
                out[idx//8]|=1<<(7-idx%8)
    return base64.b64encode(out).decode()

seen=set(); fonts=[]
for path,name in FONTS:
    if not Path(path).is_file():continue
    fonts.append(name)
    from fontTools.ttLib import TTFont
    cmap=TTFont(path).getBestCmap()
    for size in SIZES:
        font=ImageFont.truetype(path,size)
        for kind,label,text in RANKS+SUITS+RECYCLE+DISTRACTORS:
            if any(ord(c) not in cmap for c in text):continue
            im=Image.new('L',(160,160),255)
            ImageDraw.Draw(im).text((15,10),text,font=font,fill=0)
            for threshold in (120,170):
                packed=norm(im,threshold)
                if packed:seen.add((kind,label,packed))
asset=ROOT/'app/src/main/assets/offline_card_glyphs.txt'
asset.write_text('# v1: kind|label|base64 packed 24x24 bits; normalized into centered 20x20 area.\n'+'\n'.join('|'.join(x) for x in sorted(seen))+'\n')
(ROOT/'app/src/main/assets/offline_card_glyphs_manifest.json').write_text(json.dumps({'format':1,'patterns':len(seen),'fonts':fonts,'pixel_sizes':SIZES,'thresholds':[120,170],'normalization':'24x24 binary, aspect-preserving fit20, nearest-neighbour','ranks':'A23456789TJQK','suits':'CDHS','suit_shapes':'filled and hollow','recycle_symbols':'↻↺⟳⟲','distractors':'O01ILBX?ZS and unrelated shapes','purpose':'Local conservative shape recognition. Match scores are similarities, not calibrated probabilities. No phone accuracy claim.'},ensure_ascii=False,indent=2)+'\n')
print(asset,len(seen),asset.stat().st_size)
