#!/usr/bin/env python3
"""Deterministic synthetic RGB cards, rendered independently at held-out sizes.
This is not a real-device screenshot accuracy benchmark.
"""
from pathlib import Path
from PIL import Image,ImageDraw,ImageFont
import json
ROOT=Path(__file__).resolve().parent
OUT=ROOT/'fixtures';OUT.mkdir(exist_ok=True)
suits={'C':'♣','D':'♦','H':'♥','S':'♠'}
fonts={
 'dejavu_sans':'/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf',
 'dejavu_serif':'/usr/share/fonts/truetype/dejavu/DejaVuSerif.ttf',
 'liberation_serif':'/usr/share/fonts/truetype/liberation/LiberationSerif-Regular.ttf',
 'noto_sans_bold':'/usr/share/fonts/truetype/noto/NotoSans-Bold.ttf',
}
suitfile='/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf'
rows=[]
for family,fontfile in fonts.items():
 if not Path(fontfile).exists():continue
 for size in (19,25,33):
  f=ImageFont.truetype(fontfile,size);sf=ImageFont.truetype(suitfile,size)
  w=int(size*4.6);h=int(size*6.2)
  for rank in 'A23456789TJQK':
   for suit,glyph in suits.items():
    im=Image.new('RGB',(w,h),(250,250,247));d=ImageDraw.Draw(im)
    d.rectangle((0,0,w-1,h-1),outline=(100,100,100),width=1)
    ink=(198,19,27) if suit in 'DH' else (18,18,18)
    d.text((int(w*.08),int(h*.06)),'10' if rank=='T' else rank,font=f,anchor='lt',fill=ink)
    d.text((int(w*.09),int(h*.27)),glyph,font=sf,anchor='lt',fill=ink)
    name=f'{family}_{size}_{rank}{suit}.png';im.save(OUT/name)
    rows.append(f'{name}|{rank}{suit}')
# The same card font with two distinct legal corner positions, not fixed crops.
for position,rx,ry,sx,sy in [('high',.055,.04,.06,.22),('low',.11,.12,.13,.36)]:
 size=33;w=int(size*4.6);h=int(size*6.2);font=ImageFont.truetype(fonts['dejavu_sans'],size)
 for rank in 'A23456789TJQK':
  for suit,glyph in suits.items():
   im=Image.new('RGB',(w,h),(250,250,247));d=ImageDraw.Draw(im)
   ink=(198,19,27) if suit in 'DH' else (18,18,18)
   d.text((int(w*rx),int(h*ry)),'10' if rank=='T' else rank,font=font,anchor='lt',fill=ink)
   d.text((int(w*sx),int(h*sy)),glyph,font=font,anchor='lt',fill=ink)
   name=f'shift_{position}_{rank}{suit}.png';im.save(OUT/name);rows.append(name+'|'+rank+suit)
# Rejections: positive-colored background, no glyphs, only suit, incorrect suit/rank colors, missing/incomplete rank.
w,h=115,155
for case in ('blank','blue_back','missing_rank','wrong_suit_color','mixed_colors','unsupported_O','cropped_rank','noise'):
 im=Image.new('RGB',(w,h),(250,250,247));d=ImageDraw.Draw(im);f=ImageFont.truetype(fonts['dejavu_sans'],25)
 if case=='blue_back':
  im=Image.new('RGB',(w,h),(20,60,140));d=ImageDraw.Draw(im)
  for yy in range(8,h-8,8):d.line((8,yy,w-8,yy),fill=(150,180,225),width=2)
 elif case not in ('blank','noise'):
  if case!='missing_rank':d.text((9,9),'O' if case=='unsupported_O' else 'Q',font=f,anchor='lt',fill=(200,10,10) if case in ('wrong_suit_color','mixed_colors') else (18,18,18))
  d.text((10,42),'♠',font=f,anchor='lt',fill=(200,10,10) if case=='wrong_suit_color' else (18,18,18))
  if case=='cropped_rank':d.rectangle((8,9,21,17),fill=(250,250,247))
 elif case=='noise':
  import random
  r=random.Random(23)
  for i in range(150):
   xx=r.randrange(5,50);yy=r.randrange(5,80);d.point((xx,yy),fill=(15,15,15))
 name=case+'.png';im.save(OUT/name);rows.append(name+'|-')
# Non-rank glyph distractors at another unseen size, retaining a valid suit.
for family in ('dejavu_sans','noto_sans_bold'):
 font=ImageFont.truetype(fonts[family],27);sf=ImageFont.truetype(suitfile,27)
 for label,glyph in enumerate('O01ILBX?ZS'):
  im=Image.new('RGB',(124,167),(250,250,247));d=ImageDraw.Draw(im)
  d.text((10,10),glyph,font=font,anchor='lt',fill=(18,18,18))
  d.text((11,45),'♠',font=sf,anchor='lt',fill=(18,18,18))
  name=f'distractor_{family}_{label}.png';im.save(OUT/name);rows.append(name+'|-')
# Four slot-icon positives on both pale and green flat backgrounds.
for suit,glyph in list(suits.items())+list(zip('CDHS','♧♢♡♤')):
 for style in ('pale','green'):
  im=Image.new('RGB',(100,140),(245,245,240) if style=='pale' else (20,92,63));d=ImageDraw.Draw(im)
  sf=ImageFont.truetype(suitfile,39)
  ink=((200,16,28) if suit in 'DH' else (16,16,16)) if style=='pale' else (235,235,235)
  d.text((50,70),glyph,font=sf,anchor='mm',fill=ink)
  name=f'slot_{style}_{suit}_{ord(glyph)}.png';im.save(OUT/name);rows.append(name+'|SUIT:'+suit)
# Circular-arrow affordances and non-arrow controls.
for label,glyph in [('clockwise','↻'),('anticlockwise','↺'),('circle','●'),('blank','')]:
 for style in ('pale','green'):
  im=Image.new('RGB',(100,140),(245,245,240) if style=='pale' else (20,92,63));d=ImageDraw.Draw(im)
  sf=ImageFont.truetype(suitfile,39);ink=(20,20,20) if style=='pale' else (235,235,235)
  d.text((50,70),glyph,font=sf,anchor='mm',fill=ink)
  name=f'recycle_{style}_{label}.png';im.save(OUT/name)
  rows.append(name+'|'+('RECYCLE' if label in ('clockwise','anticlockwise') else 'NO_RECYCLE'))
(OUT/'manifest.txt').write_text('\n'.join(rows)+'\n')
print('Generated',len(rows),'independent RGB fixtures')
