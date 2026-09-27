"""FrostOrtho の最終キーマップ定義(ファームウェアとガイドの両方がここから作られる)"""
import json,copy
import os
SRC=os.path.join(os.path.dirname(os.path.abspath(__file__)),'conductor-keymap.json')
d=json.load(open(SRC))
def R(zmk,label,short='',hold='',kind='key'):
    return {'type':'raw','zmk':zmk,'label':label,'short':short,'hold':hold,'kind':kind}
NONE={'keyCode':'NONE','type':'none','label':''}
TRANS={'keyCode':'TRANS','type':'transparent','label':''}
L=d['layers']
# マウス(4): 1つ左へ。空きは透過。左手に編集操作、Oにドラッグ固定
m=L[4]['bindings']
m['R00'],m['R01'],m['R02'],m['R03']=m['R01'],m['R02'],m['R03'],NONE
for k,v in list(m.items()):
    if v['type']=='none': m[k]=dict(TRANS)
m['L20']=R('&mt LSHIFT LG(Z)','取り消し','⌘Z','⇧','mt')
m['L21']=R('&kp LG(X)','切り取り','⌘X')
m['L22']=R('&kp LG(C)','コピー','⌘C')
m['L23']=R('&kp LG(V)','貼り付け','⌘V')
m['R03']=R('&drag_lock','ドラッグ固定','再押しで解除')
# レイヤー10 = DeXマウス(マウス中かつDeXモード中に自動で重なる)
L[10]['name']='DeX Mouse'
for k in L[10]['bindings']: L[10]['bindings'][k]=dict(TRANS)
b10=L[10]['bindings']
b10['L20']=R('&mt LSHIFT LC(Z)','取り消し','⌃Z','⇧','mt')
b10['L21']=R('&kp LC(X)','切り取り','⌃X')
b10['L22']=R('&kp LC(C)','コピー','⌃C')
b10['L23']=R('&kp LC(V)','貼り付け','⌃V')
# DeX移動(9): 窓の左右寄せ
L[9]['bindings']['R21']=R('&kp LG(LBKT)','左半分','⌘[')
L[9]['bindings']['R23']=R('&kp LG(RBKT)','右半分','⌘]')
# 設定(6)
L[6]['bindings']['R14']=dict(NONE)
L[6]['bindings']['R20']=R('&studio_unlock','Studio解除')
# 基本(0): スクショはクリップボードへ
L[0]['bindings']['R33']=R('&kp LG(LC(LS(N4)))','スクショ','⌘Vで貼れる')
# ショートカット(7): Macの窓寄せ(Rectangle の標準ショートカット)
L[7]['bindings']['R21']=R('&kp LC(LA(LEFT))','左半分','⌃⌥←')
L[7]['bindings']['R22']=R('&kp LC(LA(RET))','最大化','⌃⌥↩')
L[7]['bindings']['R23']=R('&kp LC(LA(RIGHT))','右半分','⌃⌥→')
# F13 = 音声入力(Macは自作Python、DeXはガイドアプリが長押し/2回タップを見て聞き取る)
L[0]['bindings']['R32']=R('&kp F13','音声入力','長押し/2回タップ')
# ショートカット(7)に「アプリを終了」を追加
L[7]['bindings']['R20']=R('&kp LG(Q)','アプリを終了','⌘Q')
# レイヤー11 = ショートカット(DeX): Enter長押し中かつDeXモード中に自動で重なり、Fold8用のショートカットに置き換える
L[11]['name']='Shortcut (DeX)'
for k in L[11]['bindings']: L[11]['bindings'][k]=dict(TRANS)
b11=L[11]['bindings']
b11['R00']=R('&kp LC(T)','新しいタブ','⌃T')
b11['R01']=R('&kp LC(L)','アドレスバー','⌃L')
b11['R03']=R('&kp LG(BSPC)','戻る','⌘Bksp')
b11['R04']=R('&kp LC(LS(A))','タブを検索','⌃⇧A')
b11['R10']=R('&kp LC(W)','タブを閉じる','⌃W')
b11['R11']=R('&kp LC(LS(T))','タブ復活','⌃⇧T')
b11['R13']=R('&kp LA(RIGHT)','進む','Alt+→')
b11['R20']=R('&kp LA(F4)','アプリを終了','Alt+F4')
b11['R21']=R('&kp LG(LC(LEFT))','分割 左','⌘⌃←')
b11['R22']=R('&kp LG(LC(UP))','最大化','⌘⌃↑')
b11['R23']=R('&kp LG(LC(RIGHT))','分割 右','⌘⌃→')
b11['R24']=R('&kp LG(N)','通知','⌘N')
# DeX(8): スクショキーはガイドアプリの範囲スクショ
L[8]['bindings']['R33']=R('&none','範囲スクショ','ガイドアプリ')  # キーは何も送らず、ガイドアプリが押されたのを見て撮る
# DeX移動(9): Fold8の画面分割・最大化
L[9]['bindings']['R21']=R('&kp LG(LC(LEFT))','分割 左','⌘⌃←')
L[9]['bindings']['R22']=R('&kp LG(LC(UP))','最大化','⌘⌃↑')
L[9]['bindings']['R23']=R('&kp LG(LC(RIGHT))','分割 右','⌘⌃→')
# FrostOrthoにしかないキー(Iの下)
EXTRA={0:R('&caps_word','大文字','Caps Word')}
# エンコーダーの役割
SENSOR={0:('rsr_scroll','スクロール'),1:('rsr_zoom','拡大・縮小'),2:('rsr_vol','音量'),3:('rsr_tab','タブ切替')}
ORDER=[]
for r in range(3): ORDER+=[f"L{r}{c}" for c in range(5)]+[f"R{r}{c}" for c in range(5)]
ORDER+=[f"L3{c}" for c in range(6)]+["R30","R31",None,"R32","R33"]
IDX={k:i for i,k in enumerate(ORDER) if k}
