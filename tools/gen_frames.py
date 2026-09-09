#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成「液态玻璃 + 动态天气」动画帧
每组 6 帧, 帧与帧之间轻微位移, ViewFlipper 交叉淡变后形成流动效果。
"""
import math, os, random
from PIL import Image, ImageDraw, ImageFilter

W, H = 620, 400
RADIUS = 52
OUT = '/workspace/glasswidget/res/drawable-nodpi'
os.makedirs(OUT, exist_ok=True)

PALS = {
    'clear_day':  [(120,200,255,70),(70,130,255,60),(200,170,110,45)],
    'clear_night':[(45,60,125,85),(95,70,170,55),(30,50,110,70)],
    'cloud_day':  [(150,180,215,70),(205,218,240,55),(110,150,205,50)],
    'overcast':   [(95,110,140,75),(130,140,165,60),(75,90,120,65)],
    'rain':       [(75,115,175,80),(55,95,160,70),(125,170,220,55)],
    'thunder':    [(65,75,150,85),(105,80,190,60),(50,65,145,70)],
    'snow':       [(160,190,225,75),(205,225,245,55),(120,160,215,60)],
    'fog':        [(165,175,190,65),(195,205,220,55),(135,150,170,60)],
}

def liquid_layer(key, ph):
    """小画布画光斑再放大 = 液态玻璃渐变背景"""
    SW, SH = W//2, H//2
    img = Image.new('RGBA', (SW, SH), (0,0,0,0))
    d = ImageDraw.Draw(img)
    pal = PALS[key]
    n = len(pal)
    for i, col in enumerate(pal):
        # 光斑位置随帧缓慢流动(正弦)
        t = ph*2*math.pi
        cx = SW*(0.20+0.60*((i+1)%n)/n + 0.08*math.sin(t + i*2.1))
        cy = SH*(0.25+0.55*(i%2) + 0.12*math.cos(t + i*1.7))
        r  = SH*(0.32+0.06*math.sin(t + i*0.9))
        a  = col[3]
        layer = Image.new('RGBA',(SW,SH),(0,0,0,0))
        dl = ImageDraw.Draw(layer)
        dl.ellipse([cx-r,cy-r*0.7,cx+r,cy+r*0.7], fill=col[:3]+(a,))
        layer = layer.filter(ImageFilter.GaussianBlur(SW*0.06))
        img.alpha_composite(layer)
    # 顶部柔光
    sheen = Image.new('RGBA',(SW,SH),(0,0,0,0))
    ImageDraw.Draw(sheen).rectangle([0,0,SW,SH], fill=(255,255,255,26))
    sheen = sheen.filter(ImageFilter.GaussianBlur(SW*0.03))
    img.alpha_composite(sheen)
    return img.resize((W,H), Image.LANCZOS)

def blur_layer(im, r):
    return im.filter(ImageFilter.GaussianBlur(r))

# ---------- 天气动态元素 ----------

def add_clear_day(c, ph):
    cx, cy = int(W*0.80), int(H*0.26)
    lay = Image.new('RGBA',(W,H),(0,0,0,0)); d=ImageDraw.Draw(lay)
    # 光芒(随帧缓慢旋转)
    for k in range(12):
        ang = ph*(math.pi/6) + k*(math.pi/6)
        x1=cx+44*math.cos(ang); y1=cy+44*math.sin(ang)
        x2=cx+90*math.cos(ang); y2=cy+90*math.sin(ang)
        d.line([x1,y1,x2,y2], fill=(255,240,180,70), width=5)
    # 太阳
    d.ellipse([cx-40,cy-40,cx+40,cy+40], fill=(255,232,140,235))
    d.ellipse([cx-28,cy-28,cx+28,cy+28], fill=(255,248,205,255))
    c.alpha_composite(blur_layer(lay,3))

def add_clear_night(c, ph):
    lay = Image.new('RGBA',(W,H),(0,0,0,0)); d=ImageDraw.Draw(lay)
    # 星星(帧间闪烁)
    rnd=random.Random(7)
    for i in range(42):
        x=rnd.uniform(0,W); y=rnd.uniform(0,H*0.7)
        tw=0.5+0.5*math.sin(ph*2*math.pi*2 + i*1.3)
        a=int(60+140*tw)
        r=1.1+rnd.random()*1.6
        d.ellipse([x-r,y-r,x+r,y+r], fill=(235,240,255,a))
    # 月牙
    mx,my=W*0.80,H*0.24
    d.ellipse([mx-30,my-30,mx+30,my+30], fill=(255,250,225,235))
    mask=Image.new('RGBA',(W,H),(0,0,0,0))
    ImageDraw.Draw(mask).ellipse([mx-18,my-40,mx+42,my+30], fill=(0,0,0,255))
    lay.alpha_composite(mask)
    c.alpha_composite(blur_layer(lay,2))

def add_clouds(c, ph, dark=False, n=3, alpha=150):
    rnd=random.Random(11)
    speeds=[0.55,0.35,0.22][:n]
    for ci in range(n):
        span = W+320
        x0 = (ph*speeds[ci]*span + rnd.randint(0,span)) % span - 160
        y0 = 60 + ci*85 + rnd.randint(-12,12)
        col = (205,215,232,alpha) if not dark else (150,158,178,alpha+30)
        lay = Image.new('RGBA',(W,H),(0,0,0,0)); d=ImageDraw.Draw(lay)
        for ox,oy,r in [(0,0,46),(42,8,34),(-42,10,32),(8,-22,38)]:
            d.ellipse([x0+ox-r,y0+oy-r,x0+ox+r,y0+oy+r], fill=col)
        c.alpha_composite(blur_layer(lay,6))

def add_rain(c, ph, drops=80):
    rnd=random.Random(13)
    lay = Image.new('RGBA',(W,H),(0,0,0,0)); d=ImageDraw.Draw(lay)
    for k in range(drops):
        sp=rnd.uniform(0.8,1.4)
        x=rnd.uniform(0,W+60)
        base=rnd.uniform(0,H)
        # 斜向落下
        y=(base - ph*H*0.9*sp) % (H+70)
        x=x - (ph*H*0.9*sp)*0.22 % (W+60)
        a=int(60+80*rnd.random())
        d.line([x,y,x-7,y+22], fill=(210,228,255,a), width=2)
    c.alpha_composite(lay)

def add_thunder(c, ph):
    add_rain(c, ph, drops=110)
    # 闪光
    if ph in (2,5):
        lay=Image.new('RGBA',(W,H),(0,0,0,0))
        ImageDraw.Draw(lay).rectangle([0,0,W,H], fill=(235,242,255,70))
        # 闪电
        d=ImageDraw.Draw(lay)
        rnd=random.Random(5+ph)
        x=W*(0.68+0.08*rnd.random()); y=60
        pts=[(x,y)]
        while y < H*0.7:
            x += rnd.choice([-14,-8,6,12,18]); y += rnd.randint(18,40)
            pts.append((x,y))
        d.line(pts, fill=(255,255,255,220), width=6)
        c.alpha_composite(blur_layer(lay,3))

def add_snow(c, ph, flakes=70):
    rnd=random.Random(17)
    lay = Image.new('RGBA',(W,H),(0,0,0,0)); d=ImageDraw.Draw(lay)
    for k in range(flakes):
        sp=rnd.uniform(0.5,1.1)
        x=rnd.uniform(0,W)
        y=(rnd.uniform(-30,H+30) - ph*H*0.8*sp) % (H+40)
        x=(x + ph*14*sp + 20*math.sin(ph*2*math.pi*2+k)) % W
        r=2.2+rnd.random()*2.6
        a=int(120+100*rnd.random())
        d.ellipse([x-r,y-r,x+r,y+r], fill=(235,242,255,a))
    c.alpha_composite(blur_layer(lay,2))

def add_fog(c, ph):
    rnd=random.Random(23)
    for band,(y0,h,sp,a) in enumerate([(0.30,60,0.9,55),(0.55,80,0.6,70),(0.78,60,0.4,50)]):
        lay=Image.new('RGBA',(W,H),(0,0,0,0))
        span=W+500
        x0=(ph*sp*span + rnd.randint(0,span)) % span - 250
        y=y0*H
        d=ImageDraw.Draw(lay)
        d.ellipse([x0-180,y-h,x0+320,y+h], fill=(230,238,248,a))
        d.ellipse([x0+60,y-h*0.7,x0+380,y+h*0.7], fill=(238,244,252,a-10))
        c.alpha_composite(blur_layer(lay,26))

def rounded(im):
    a=im.getchannel('A')
    m=Image.new('L',(W,H),0)
    ImageDraw.Draw(m).rounded_rectangle([0,0,W-1,H-1], radius=RADIUS, fill=255)
    a=Image.composite(a, Image.new('L',(W,H),0), m)
    im.putalpha(a)
    return im

FRAMES=6
def render(key):
    out=[]
    for f in range(FRAMES):
        ph = f/FRAMES
        img = liquid_layer(key, ph)
        if key=='clear_day':   add_clear_day(img, ph)
        elif key=='clear_night': add_clear_night(img, ph)
        elif key=='cloud_day': add_clouds(img, ph, dark=False)
        elif key=='overcast':  add_clouds(img, ph, dark=True, n=2, alpha=185)
        elif key=='rain':      add_rain(img, ph)
        elif key=='thunder':   add_thunder(img, ph)
        elif key=='snow':      add_snow(img, ph)
        elif key=='fog':       add_fog(img, ph)
        out.append(rounded(img))
    return out

def main():
    keys = ['clear_day','clear_night','cloud_day','overcast','rain','thunder','snow','fog']
    for key in keys:
        for f,img in enumerate(render(key)):
            p=f'{OUT}/wx_{key}_{f}.png'
            img.save(p, optimize=True)
            print(p, os.path.getsize(p)//1024, 'KB')

if __name__=='__main__':
    main()
