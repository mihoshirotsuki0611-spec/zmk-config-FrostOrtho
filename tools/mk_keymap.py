import re,sys,os
sys.path.insert(0,os.path.dirname(os.path.abspath(__file__)))
from layout import d,EXTRA,SENSOR,ORDER,IDX
def conv(b):
    t=b['type'];kc=b.get('keyCode','')
    if t=='raw': return b['zmk']
    if t=='none': return '&none'
    if t=='transparent': return '&trans'
    if t=='mod-tap': return f"&mt {b['holdAction']} {b['tapAction']}"
    if t=='layer-tap':
        n,k=re.match(r'LT\((\d+),\s*(\w+)\)',kc).groups()
        return f"&lt_bspc {n} {k}" if k=='BSPC' else f"&lt {n} {k}"
    if t=='momentary': return "&mo "+re.findall(r'\d+',kc)[0]
    if t=='toggle': return "&tog "+re.findall(r'\d+',kc)[0]
    if kc=='STUDIO_UNLOCK': return '&studio_unlock'
    if kc=='BOOTLOADER': return '&bootloader'
    if kc.startswith('BT_'): return f"&bt {kc}"
    if kc.startswith('mkp '): return '&'+kc
    if t=='custom': raise Exception(kc)
    return f"&kp {kc}"
layers=''
for l in d['layers']:
    lid=l['id']; bs=[]
    for k in ORDER:
        if k is None: bs.append(conv(EXTRA[lid]) if lid in EXTRA else ('&none' if lid==0 else '&trans'))
        else: bs.append(conv(l['bindings'][k]))
    w=max(len(x) for x in bs)+2
    rows=[bs[0:10],bs[10:20],bs[20:30],bs[30:41]]
    txt='\n'.join((''.join(x.ljust(w) for x in r[:5])+'    '+''.join(x.ljust(w) for x in r[5:])) if i<3 else (''.join(x.ljust(w) for x in r[:6])+'    '+''.join(x.ljust(w) for x in r[6:])) for i,r in enumerate(rows))
    sens=SENSOR.get(lid,('rsr_trans',''))[0]
    layers+=f"""
        layer_{lid} {{
            display-name = "{l['name']}";
            bindings = <
{txt}
            >;
            sensor-bindings = <&{sens}>;
        }};
"""
combos=''
for c in d['combos']:
    b=c['binding']
    if b['type'] in('none','custom'): continue
    combos+=f"""        combo_{c['id'].split('-')[-1]} {{
            timeout-ms = <{c['timeoutMs']}>;
            key-positions = <{' '.join(str(IDX[k]) for k in c['keyPositions'])}>;
            bindings = <{conv(b)}>;
        }};  // {c['name']}
"""
out=f"""// FrostOrtho keymap — Conductor Monokey から移植
// このファイルは自動生成です(元データ: Conductor の書き出しJSON + FrostOrtho用の変更)
#include <input/processors.dtsi>
#include <behaviors/runtime-sensor-rotate.dtsi>
#include <behaviors.dtsi>
#include <dt-bindings/zmk/bt.h>
#include <dt-bindings/zmk/keys.h>
#include <dt-bindings/zmk/pointing.h>

&mt {{
    flavor = "balanced";
    quick-tap-ms = <0>;
}};

// AML(レイヤー4)中にクリックしたらタイムアウトを延長
&mkp_input_listener {{ input-processors = <&zip_temp_layer 4 10000>; }};

/ {{
    combos {{
        compatible = "zmk,combos";
{combos}    }};

    // マウス中(4) かつ DeXモード中(8) は、DeX用の編集キー(10)を重ねる
    conditional_layers {{
        compatible = "zmk,conditional-layers";
        dex_mouse {{
            if-layers = <4 8>;
            then-layer = <10>;
        }};
        // ショートカット中(7) かつ DeXモード中(8) は、Fold8用のショートカット(11)を重ねる
        dex_shortcut {{
            if-layers = <7 8>;
            then-layer = <11>;
        }};
    }};

    behaviors {{
        // Bksp用レイヤータップ: タップ→すぐ押し直して長押しでBksp連打
        lt_bspc: lt_bspc {{
            compatible = "zmk,behavior-hold-tap";
            #binding-cells = <2>;
            flavor = "tap-preferred";
            tapping-term-ms = <200>;
            quick-tap-ms = <200>;
            bindings = <&mo>, <&kp>;
        }};

        // ドラッグ固定: 押すと左クリックを押しっぱなしに、もう一度で離す(マウスレイヤーを出ても自動で離す)
        drag_lock: drag_lock {{
            compatible = "zmk,behavior-frost-drag-lock";
            #binding-cells = <0>;
        }};

        // エンコーダー(DYA Studioからも変更可)
        // スクロール量 = 速さ(1500) × 時間(32ms)
        rsr_scroll: rsr_scroll {{
            compatible = "zmk,behavior-runtime-sensor-rotate";
            #sensor-binding-cells = <0>;
            tap-ms = <32>;
            cw-binding = <&msc MOVE_Y(-1500)>;
            ccw-binding = <&msc MOVE_Y(1500)>;
        }};
        rsr_vol: rsr_vol {{
            compatible = "zmk,behavior-runtime-sensor-rotate";
            #sensor-binding-cells = <0>;
            tap-ms = <5>;
            cw-binding = <&kp C_VOL_DN>;
            ccw-binding = <&kp C_VOL_UP>;
        }};
        rsr_tab: rsr_tab {{
            compatible = "zmk,behavior-runtime-sensor-rotate";
            #sensor-binding-cells = <0>;
            tap-ms = <5>;
            cw-binding = <&kp LC(TAB)>;
            ccw-binding = <&kp LS(LC(TAB))>;
        }};
        rsr_zoom: rsr_zoom {{
            compatible = "zmk,behavior-runtime-sensor-rotate";
            #sensor-binding-cells = <0>;
            tap-ms = <5>;
            cw-binding = <&kp LG(MINUS)>;
            ccw-binding = <&kp LG(EQUAL)>;
        }};
    }};

    keymap {{
        compatible = "zmk,keymap";
{layers}    }};
}};
"""
dst=sys.argv[1] if len(sys.argv)>1 else os.path.join(os.path.dirname(os.path.abspath(__file__)),'..','config','FrostOrtho.keymap')
open(dst,'w').write(out)
