import json,re,html
import sys
import os
HERE=os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0,HERE)
from layout import d,EXTRA,SENSOR
order=[]
for r in range(3): order+=[f"L{r}{c}" for c in range(5)]+[f"R{r}{c}" for c in range(5)]
order+=[f"L3{c}" for c in range(6)]+["R30","R31",None,"R32","R33"]
NAMES={0:'基本',1:'記号',2:'数字',3:'移動',4:'マウス',5:'スクロール',6:'設定',7:'ショートカット',8:'DeX',9:'DeX 移動',10:'マウス(DeX)',11:'ショートカット(DeX)',12:'精密',13:'ジェスチャー'}
SHORT={7:'ショート',9:'DeX移動'}
TAP={'LANG1':'かな','LANG2':'英数','FSLH':'/','SPACE':'Space','TAB':'Tab','DEL':'Del','BSPC':'Bksp','ENTER':'Enter','Z':'Z'}
MODS={'LSHIFT':'⇧','RSHIFT':'⇧','LGUI':'⌘','RGUI':'⌘','LCTRL':'⌃','RCTRL':'⌃','LALT':'⌥','RALT':'⌥'}
SPECIAL={'mkp MB1':'左クリック','mkp MB2':'右クリック','mkp MB3':'中クリック','MO(5)':'スクロール','BOOTLOADER':'Boot',
 'C_PREV':'前の曲','C_NEXT':'次の曲','C_BRI_DN':'暗く','C_BRI_UP':'明るく','UP':'↑','DOWN':'↓','LEFT':'←','RIGHT':'→','ESC':'Esc','LALT':'⌥ Alt'}
def macify(lbl):
    return (lbl.replace('G+','⌘').replace('S+','⇧').replace('C+','⌃').replace('A+','⌥')
               .replace('Left','←').replace('Right','→').replace('Up','↑').replace('Down','↓'))
ACT={'LG(LS(N4))':'スクショ','LG(LS(N5))':'録画など','LC(UP)':'窓の一覧','LC(DOWN)':'アプリの窓',
 'LC(LEFT)':'左の画面','LC(RIGHT)':'右の画面','LC(A)':'行の先頭','LC(E)':'行の末尾','LG(LEFT)':'行頭へ','LG(RIGHT)':'行末へ',
 'C_PREV':'前の曲','C_NEXT':'次の曲','C_BRI_DN':'暗く','C_BRI_UP':'明るく','LG(T)':'新しいタブ','LG(L)':'アドレスバー',
 'LS(LC(TAB))':'前のタブ','LC(TAB)':'次のタブ','LG(LBKT)':'戻る','LG(RBKT)':'進む','LG(LS(A))':'タブを検索','LG(W)':'タブを閉じる',
 'LG(LS(T))':'タブ復活','LG(TAB)':'アプリ切替','LG(K)':'クイック検索','BT_CLR':'接続先消去','BT_CLR_ALL':'全部消去',
 'BOOTLOADER':'書込モード','STUDIO_UNLOCK':'Studio解除','TG(8)':'DeXモード','TG(12)':'精密モード','SCRL_INV':''}
DEX_ACT={'LG(LBKT)':'左半分','LG(RBKT)':'右半分','C_PREV':'前の曲','C_NEXT':'次の曲','C_BRI_DN':'暗く','C_BRI_UP':'明るく','LC(A)':'行の先頭','LC(E)':'行の末尾'}
CUR=[0]
def key(b):
    r=key0(b); kc=b.get('keyCode','')
    tbl=DEX_ACT if CUR[0] in (8,9) else ACT
    if kc in tbl and r['k'] in('key','mo'):
        r['s']=r.get('t') if r.get('t') not in(tbl[kc],) else ''
        if kc.startswith('BT_') or kc in('BOOTLOADER','STUDIO_UNLOCK','TG(8)','TG(12)'): r['s']=''
        r['t']=tbl[kc]
    elif kc.startswith('BT_SEL'):
        r['t']='接続先 '+kc.split()[-1]
    return r
def key0(b):
    t=b['type'];kc=b.get('keyCode','')
    if t=='raw': return {'k':b['kind'],'t':b['label'],'s':b.get('short',''),'h':b.get('hold','')}
    if t=='transparent': return {'k':'trans'}
    if t=='none': return {'k':'none'}
    if t=='layer-tap':
        n=int(re.findall(r'\d+',kc)[0]); tp=b['tapAction']
        return {'k':'lt','t':TAP.get(tp,tp),'h':SHORT.get(n,NAMES[n]),'to':n}
    if t=='mod-tap':
        tp=b['tapAction']; return {'k':'mt','t':TAP.get(tp,tp),'h':MODS.get(b['holdAction'],b['holdAction'])}
    if t=='momentary': n=int(re.findall(r'\d+',kc)[0]); return {'k':'mo','t':NAMES[n],'h':'押す間','to':n}
    if t=='toggle': n=int(re.findall(r'\d+',kc)[0]); return {'k':'mo','t':NAMES[n],'h':'切替'}
    if kc in SPECIAL: return {'k':'key','t':SPECIAL[kc]}
    return {'k':'key','t':macify(b.get('label') or kc)}
HINT={0:'ふつうの文字入力',1:'記号 と Bluetooth',2:'数字 と Fキー',3:'カーソル・デスクトップ移動・音楽',4:'クリック・編集 / Uを押しながら転がすとスクロール',11:'タブ・画面分割・通知など(Fold8用)',10:'クリック・編集(DeX用) / Uを押しながら転がすとスクロール',
 5:'ボールを転がしてスクロール(縦・横)',6:'接続先の切り替え・リセット',7:'タブとウィンドウの操作',8:'DeXモード中',9:'DeXのウィンドウ・移動',12:'カーソルをゆっくり動かす',13:'ジェスチャー'}
TONE={0:0,1:1,2:2,3:3,4:4,5:5,6:6,7:7,8:2,9:3,12:4,13:5}
layers=[]
for l in d['layers']:
    CUR[0]=l['id']; ks=[]
    for k in order:
        ks.append((key(EXTRA[l['id']]) if l['id'] in EXTRA else {'k':'none' if l['id']==0 else 'trans'}) if k is None else key(l['bindings'][k]))
    layers.append({'enc':SENSOR.get(l['id'],(None,''))[1],'name':NAMES[l['id']],'hint':HINT.get(l['id'],''),'tone':TONE.get(l['id'],0),'keys':ks})
# FrostOrtho独自: BkspはタップしてすぐにBksp長押しで連打
for li,pos in ((0,36),(8,36)): layers[li]['keys'][pos]['h']=layers[li]['keys'][pos]['h']
for li in (0,8):
    layers[li]['keys'][36]['s']='2回目長押し=連打'
TAPN={'COMMA':',','DOT':'.'}
combos=[]
for c in d['combos']:
    b=c['binding']
    if b['type'] in('none','custom'): continue
    keys='+'.join(TAPN.get(d['layers'][0]['bindings'][k]['keyCode'],d['layers'][0]['bindings'][k].get('tapAction') or d['layers'][0]['bindings'][k]['keyCode']) for k in c['keyPositions'])
    what={'MO(5)':'スクロール','MO(13)':'ジェスチャー','BT_SEL 5':'接続先5','BOOTLOADER':'書込モード'}.get(b['keyCode'],c['name'])
    combos.append({'keys':keys,'what':what})
tpl=open(os.path.join(HERE,'guide_tpl.html')).read()
tpl=tpl.replace('/*COMBOS*/[]',json.dumps(combos,ensure_ascii=False))
_out=(tpl.replace('/*LAYERS*/[]',json.dumps(layers,ensure_ascii=False)))
print('ok',len(layers))
for _p in (os.path.join(HERE,'..','guide','FrostOrtho レイヤーガイド.html'),
           os.path.join(HERE,'..','android','app','src','main','assets','guide.html')):
    os.makedirs(os.path.dirname(_p),exist_ok=True); open(_p,'w').write(_out)
