/*
 * FrostOrtho の小さな便利機能
 *  - DeXモードの自動切替: 接続先を切り替えたら、スマホの接続先ならDeXモード(レイヤー8)をオン、それ以外はオフ
 *  - 電池残量の通知: レイヤーガイドに左右の電池残量を送る(Raw HID)
 */
#include <zephyr/kernel.h>
#include <zephyr/init.h>
#include <zephyr/device.h>
#include <zephyr/toolchain.h>
#include <string.h>
#include <zmk/event_manager.h>
#include <zmk/keymap.h>
#include <zmk/ble.h>
#include <zmk/battery.h>
#include <zmk/endpoints.h>
#include <zmk/events/ble_active_profile_changed.h>
#include <zmk/events/battery_state_changed.h>

#define DEX_LAYER 8

/* ---------- DeXモードの自動切替 ---------- */
static void apply_dex_for_profile(int idx) {
    bool dex = (CONFIG_FROST_DEX_PROFILES >> idx) & 1;
    if (dex) {
        zmk_keymap_layer_activate(DEX_LAYER);
    } else {
        zmk_keymap_layer_deactivate(DEX_LAYER);
    }
}

static int profile_listener(const zmk_event_t *eh) {
    const struct zmk_ble_active_profile_changed *ev = as_zmk_ble_active_profile_changed(eh);
    if (ev) {
        apply_dex_for_profile(ev->index);
    }
    return ZMK_EV_EVENT_BUBBLE;
}
ZMK_LISTENER(frost_dex_auto, profile_listener);
ZMK_SUBSCRIPTION(frost_dex_auto, zmk_ble_active_profile_changed);

/* 起動直後(設定の読み込み後)にも1回そろえる */
static void dex_boot_work_cb(struct k_work *work) { apply_dex_for_profile(zmk_ble_active_profile_index()); }
static K_WORK_DELAYABLE_DEFINE(dex_boot_work, dex_boot_work_cb);

/* ---------- 電池残量をガイドへ ---------- */
#if IS_ENABLED(CONFIG_RAW_HID)
#include <raw_hid/events.h>
#if IS_ENABLED(CONFIG_ZMK_SPLIT_BLE_CENTRAL_BATTERY_LEVEL_FETCHING)
#include <zmk/split/central.h>
#endif

#define BATTERY_PACKET_MARKER 0xB1
static uint8_t batt_buf[CONFIG_RAW_HID_REPORT_SIZE];

static void send_battery(void) {
    uint8_t right = zmk_battery_state_of_charge();
    uint8_t left = 0xFF;
#if IS_ENABLED(CONFIG_ZMK_SPLIT_BLE_CENTRAL_BATTERY_LEVEL_FETCHING)
    uint8_t lv;
    if (zmk_split_central_get_peripheral_battery_level(0, &lv) == 0) {
        left = lv;
    }
#endif
    memset(batt_buf, 0, sizeof(batt_buf));
    batt_buf[0] = BATTERY_PACKET_MARKER;
    batt_buf[1] = left;
    batt_buf[2] = right;
    raise_raw_hid_sent_event((struct raw_hid_sent_event){.data = batt_buf, .length = sizeof(batt_buf)});
}

/* ---------- 状態をガイドへ(大文字モード・ドラッグ固定・接続先・スマホへ入力中か) ---------- */
#define STATUS_PACKET_MARKER 0xC1
__weak bool frost_drag_lock_held(void) { return false; }

/* 大文字モード(caps_word)は状態の通知がないため、ZMK内部の状態(先頭の bool active)を読む */
#if DT_NODE_EXISTS(DT_NODELABEL(caps_word))
struct caps_word_data_head { bool active; };
static const struct device *caps_dev = DEVICE_DT_GET(DT_NODELABEL(caps_word));
static bool caps_active(void) { return ((struct caps_word_data_head *)caps_dev->data)->active; }
#else
static bool caps_active(void) { return false; }
#endif

static uint8_t status_buf[CONFIG_RAW_HID_REPORT_SIZE];
static uint8_t last_status[5] = {0xFF, 0xFF, 0xFF, 0xFF, 0xFF};

/* いまキー入力がスマホ(DeXの接続先)へ届いているか。USB出力中や Mac の接続先なら 0 */
static bool keys_to_phone(void) {
    struct zmk_endpoint_instance ep = zmk_endpoints_selected();
    if (ep.transport != ZMK_TRANSPORT_BLE) {
        return false;
    }
    return (CONFIG_FROST_DEX_PROFILES >> zmk_ble_active_profile_index()) & 1;
}

static void send_status(bool force) {
    uint8_t now[5] = {caps_active(), frost_drag_lock_held(), (uint8_t)zmk_ble_active_profile_index(),
                      zmk_ble_active_profile_is_connected(), keys_to_phone()};
    if (!force && memcmp(now, last_status, sizeof(now)) == 0) {
        return;
    }
    memcpy(last_status, now, sizeof(now));
    memset(status_buf, 0, sizeof(status_buf));
    status_buf[0] = STATUS_PACKET_MARKER;
    memcpy(&status_buf[1], now, sizeof(now));
    raise_raw_hid_sent_event((struct raw_hid_sent_event){.data = status_buf, .length = sizeof(status_buf)});
}

static void status_work_cb(struct k_work *work);
static K_WORK_DELAYABLE_DEFINE(status_work, status_work_cb);
static void status_work_cb(struct k_work *work) {
    send_status(false);
    k_work_schedule(&status_work, K_MSEC(100));
}

static void batt_work_cb(struct k_work *work);
static K_WORK_DELAYABLE_DEFINE(batt_work, batt_work_cb);
static void batt_work_cb(struct k_work *work) {
    send_battery();
    send_status(true);
    k_work_schedule(&batt_work, K_SECONDS(30));
}

/* Android版ガイドがつながった直後に、状態をすぐ送る */
void frost_features_request_status(void) { k_work_reschedule(&batt_work, K_MSEC(300)); }

static int battery_listener(const zmk_event_t *eh) {
    k_work_reschedule(&batt_work, K_MSEC(200));
    return ZMK_EV_EVENT_BUBBLE;
}
ZMK_LISTENER(frost_battery, battery_listener);
ZMK_SUBSCRIPTION(frost_battery, zmk_battery_state_changed);
#if IS_ENABLED(CONFIG_ZMK_SPLIT_BLE_CENTRAL_BATTERY_LEVEL_FETCHING)
ZMK_SUBSCRIPTION(frost_battery, zmk_peripheral_battery_state_changed);
#endif
#endif

static int frost_features_init(void) {
    k_work_schedule(&dex_boot_work, K_SECONDS(3));
#if IS_ENABLED(CONFIG_RAW_HID)
    k_work_schedule(&batt_work, K_SECONDS(5));
    k_work_schedule(&status_work, K_SECONDS(2));
#endif
    return 0;
}
SYS_INIT(frost_features_init, APPLICATION, CONFIG_APPLICATION_INIT_PRIORITY);
