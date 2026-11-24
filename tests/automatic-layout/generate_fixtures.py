#!/usr/bin/env python3
"""Generated boards for geometry/state transitions; not real phone screenshots."""
from pathlib import Path
from PIL import Image,ImageDraw,ImageFont
out=Path(__file__).resolve().parent/'fixtures';out.mkdir(exist_ok=True)
font='/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf'
f=ImageFont.truetype(font,25);sf=ImageFont.truetype(font,39)
W,H=920,1850;cw,ch=115,155;xs=[20+i*127 for i in range(7)];top=650;fy=450
felt=(20,92,63);suits={'C':'♣','S':'♠','H':'♥','D':'♦'}
codes=['AS','KD','QC','JH','TC','9D','8S']
def face(im,x,y,code):
 d=ImageDraw.Draw(im);d.rectangle((x,y,x+cw-1,y+ch-1),fill=(250,250,247),outline=(100,100,100),width=1)
 rank,suit=code[:-1],code[-1];ink=(198,19,27) if suit in 'DH' else (18,18,18)
 d.text((x+int(cw*.08),y+int(ch*.06)),'10' if rank=='T' else rank,font=f,anchor='lt',fill=ink)
 d.text((x+int(cw*.09),y+int(ch*.27)),suits[suit],font=f,anchor='lt',fill=ink)
def back(im,x,y):
 d=ImageDraw.Draw(im);d.rectangle((x,y,x+cw-1,y+ch-1),fill=(20,60,140),outline=(240,240,240),width=2)
 for yy in range(y+8,y+ch-8,8):d.line((x+8,yy,x+cw-8,yy),fill=(150,180,225),width=2)
def slot(im,i,suit):
 d=ImageDraw.Draw(im);x=xs[i]
 d.rounded_rectangle((x,fy,x+cw-1,fy+ch-1),radius=5,outline=(80,140,112),width=1)
 d.text((x+cw/2,fy+ch/2),suits[suit],font=sf,anchor='mm',fill=(235,235,235))
def fresh(order='CSHD'):
 im=Image.new('RGB',(W,H),felt)
 for i,suit in enumerate(order):slot(im,i,suit)
 back(im,xs[6],fy)
 for i,code in enumerate(codes):
  if i:back(im,xs[i],top)
  face(im,xs[i],top+i*20,code)
 return im
im=fresh();im.save(out/'initial.png')
a=im.copy();ImageDraw.Draw(a).rectangle((0,1300,W-1,1340),fill=(12,22,30));a.save(out/'footer.png')
im.resize((1380,2775),Image.Resampling.NEAREST).save(out/'scaled.png')
a=im.copy();d=ImageDraw.Draw(a)
for i,suit in enumerate('CSHD'):
 d.rectangle((xs[i],fy,xs[i]+cw-1,fy+ch-1),fill=(250,250,247),outline=(100,100,100))
 d.text((xs[i]+cw/2,fy+ch/2),suits[suit],font=sf,anchor='mm',fill=(200,16,28) if suit in 'DH' else (16,16,16))
a.save(out/'pale_foundations.png')
a=im.copy();d=ImageDraw.Draw(a);d.rectangle((xs[0],top,xs[0]+cw,top+ch),fill=felt);face(a,xs[1],fy,'AS');a.save(out/'foundation_and_empty.png')
a=im.copy();d=ImageDraw.Draw(a);d.rectangle((xs[1],top,xs[1]+cw,top+ch+30),fill=felt);back(a,xs[1],top);a.save(out/'exposed_back.png')
a=im.copy();d=ImageDraw.Draw(a);d.rectangle((xs[1],top,xs[1]+cw,top+ch+30),fill=felt);face(a,xs[1],top,'KH');a.save(out/'flipped.png')
a=im.copy();face(a,xs[4]+44,fy,'5H');a.save(out/'drawn.png')
a=im.copy();d=ImageDraw.Draw(a);d.rectangle((xs[6],fy,xs[6]+cw,fy+ch),fill=felt);d.text((xs[6]+cw/2,fy+ch/2),'↻',font=sf,anchor='mm',fill=(235,235,235));face(a,xs[4]+44,fy,'5H');a.save(out/'recycle.png')
# A white border can bridge the blue back strips to a lower exposed face;
# connected component height must not become the actual card height.
a=im.copy(); d=ImageDraw.Draw(a)
for i in range(1,7):
 d.line((xs[i]+2, top, xs[i]+2, top+i*20+2),fill=(250,250,247),width=3)
a.save(out/'connected_backs.png')
fresh('DHCS').save(out/'permuted_foundations.png')
a=im.copy();ImageDraw.Draw(a).rectangle((xs[0],fy,xs[0]+cw,fy+ch),fill=felt);a.save(out/'missing_foundation_icon.png')
a=im.copy();ImageDraw.Draw(a).rectangle((xs[4]-2,top-30,xs[5]+cw+2,top+ch+160),fill=(240,240,240));a.save(out/'occluded.png')
Image.new('RGB',(W,H),felt).save(out/'blank.png')
a=im.copy();ImageDraw.Draw(a).rectangle((xs[0],top+60,xs[0]+cw,top+ch-1),fill=felt);a.save(out/'truncated_face.png')
a=im.copy();ImageDraw.Draw(a).rectangle((xs[6],fy,xs[6]+cw,fy+ch),fill=(240,240,240));a.save(out/'unknown_stock.png')
print('Generated',len(list(out.glob('*.png'))),'synthetic board fixtures')
