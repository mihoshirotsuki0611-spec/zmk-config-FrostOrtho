/*
 * FrostOrtho ステータスLED(Conductor風)
 *
 * 右手側XIAOのRGB LEDを次の優先順位で光らせる。
 *   1. 色付きレイヤーが有効な間 : そのレイヤーの色で点灯
 *   1b. つまみを回している間  : 水色(レイヤー中はレイヤーの色)
 *   2. USB出力中               : 消灯(電池節約)
 *   3. Bluetooth 接続中         : 消灯(電池節約)
 *   4. 接続先あり・待機中       : 接続先の色でゆっくり点滅
 *   5. 接続先なし・待機中       : 接続先の色で速く点滅
 *
 * 色の番号: 0=消灯 1=赤 2=緑 3=黄 4=青 5=マゼンタ 6=シアン 7=白
 * (bit0=赤, bit1=緑, bit2=青)
 */
#include <zephyr/kernel.h>
#include <zephyr/init.h>
#include <zephyr/drivers/gpio.h>
#include <zmk/ble.h>
#include <zmk/endpoints.h>
#include <zmk/keymap.h>
#include <zmk/event_manager.h>
#include <zmk/events/sensor_event.h>

#define OFF 0
#define RED 1
#define GREEN 2
#define YELLOW 3
#define BLUE 4
#define MAGENTA 5
#define CYAN 6
#define WHITE 7

/* 接続先ごとの色(BT 0〜5) */
static const uint8_t profile_colors[] = {BLUE, GREEN, RED, YELLOW, CYAN, MAGENTA};

/* レイヤーごとの色(ConductorのledColorと同じ。0=色なし→接続状態を表示) */
static const uint8_t layer_colors[] = {
    OFF,     /* 0 base */
    RED,     /* 1 symbol */
    GREEN,   /* 2 number */
    YELLOW,  /* 3 move */
    OFF,     /* 4 mouse(ボールを触るたびに光るので消灯) */
    OFF,     /* 5 scroll */
    BLUE,    /* 6 setting */
    WHITE,   /* 7 ショートカット */
    OFF,     /* 8 DeX */
    OFF,     /* 9 DeX Move */
    OFF,     /* 10 */
    OFF,     /* 11 */
    OFF,     /* 12 Precision */
    OFF,     /* 13 Gesture */
};

#define TICK_MS 50

/* つまみを回している間(と止めてから少し)は水色に光らせる */
#define KNOB_COLOR CYAN
#define KNOB_HOLD_MS 500
static int64_t knob_until;

static int knob_listener(const zmk_event_t *eh) {
    knob_until = k_uptime_get() + KNOB_HOLD_MS;
    return ZMK_EV_EVENT_BUBBLE;
}
ZMK_LISTENER(frost_knob_led, knob_listener);
ZMK_SUBSCRIPTION(frost_knob_led, zmk_sensor_event);

static const struct gpio_dt_spec leds[3] = {
    GPIO_DT_SPEC_GET(DT_ALIAS(led0), gpios), /* 赤 */
    GPIO_DT_SPEC_GET(DT_ALIAS(led1), gpios), /* 緑 */
    GPIO_DT_SPEC_GET(DT_ALIAS(led2), gpios), /* 青 */
};

static void set_color(uint8_t color) {
    for (int i = 0; i < 3; i++) {
        gpio_pin_set_dt(&leds[i], (color >> i) & 1);
    }
}

static bool blink_on(uint32_t half_ms) {
    return ((k_uptime_get_32() / half_ms) % 2) == 0;
}

static void status_led_tick(struct k_work *work);
static K_WORK_DELAYABLE_DEFINE(status_led_work, status_led_tick);

static void status_led_tick(struct k_work *work) {
    uint8_t color = OFF;
    /* 有効なレイヤーのうち、色がついている一番上のもの(DeXモードのように色なしのレイヤーは飛ばす) */
    uint8_t layer_color = OFF;
    for (int i = ARRAY_SIZE(layer_colors) - 1; i > 0; i--) {
        if (layer_colors[i] != OFF && zmk_keymap_layer_active(i)) {
            layer_color = layer_colors[i];
            break;
        }
    }

    if (layer_color != OFF) {
        color = layer_color;
    } else if (k_uptime_get() < knob_until) {
        color = KNOB_COLOR;
    } else if (zmk_endpoints_selected().transport == ZMK_TRANSPORT_USB) {
        color = OFF;
    } else {
        int idx = zmk_ble_active_profile_index();
        uint8_t pc = profile_colors[idx % ARRAY_SIZE(profile_colors)];
        if (zmk_ble_active_profile_is_connected()) {
            color = OFF; /* つながっている間は光らせない(電池節約) */
        } else if (zmk_ble_active_profile_is_open()) {
            color = blink_on(CONFIG_FROST_STATUS_LED_FAST_BLINK_MS) ? pc : OFF;
        } else {
            color = blink_on(CONFIG_FROST_STATUS_LED_SLOW_BLINK_MS) ? pc : OFF;
        }
    }

    set_color(color);
    k_work_schedule(&status_led_work, K_MSEC(TICK_MS));
}

static int status_led_init(void) {
    for (int i = 0; i < 3; i++) {
        if (!gpio_is_ready_dt(&leds[i])) {
            return -ENODEV;
        }
        gpio_pin_configure_dt(&leds[i], GPIO_OUTPUT_INACTIVE);
    }
    k_work_schedule(&status_led_work, K_MSEC(500));
    return 0;
}

SYS_INIT(status_led_init, APPLICATION, CONFIG_APPLICATION_INIT_PRIORITY);
