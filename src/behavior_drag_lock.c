/*
 * ドラッグ固定: 押すたびに左クリックの「押しっぱなし/離す」を切り替える。
 * マウスレイヤー(4)から出たら自動で離す(押しっぱなしで取り残さないため)。
 */
#define DT_DRV_COMPAT zmk_behavior_frost_drag_lock

#include <zephyr/device.h>
#include <zephyr/input/input.h>
#include <drivers/behavior.h>
#include <zmk/behavior.h>
#include <zmk/event_manager.h>
#include <zmk/events/layer_state_changed.h>
#include <zmk/keymap.h>

#if DT_HAS_COMPAT_STATUS_OKAY(DT_DRV_COMPAT)

#define MOUSE_LAYER 4

static const struct device *mkp_dev = DEVICE_DT_GET(DT_NODELABEL(mkp));
static bool held;

bool frost_drag_lock_held(void) { return held; }

static void set_held(bool on) {
    if (held == on) {
        return;
    }
    held = on;
    input_report_key(mkp_dev, INPUT_BTN_0, on ? 1 : 0, true, K_FOREVER);
}

static int on_pressed(struct zmk_behavior_binding *binding, struct zmk_behavior_binding_event event) {
    set_held(!held);
    return ZMK_BEHAVIOR_OPAQUE;
}

static int on_released(struct zmk_behavior_binding *binding, struct zmk_behavior_binding_event event) {
    return ZMK_BEHAVIOR_OPAQUE;
}

static const struct behavior_driver_api drag_lock_api = {
    .binding_pressed = on_pressed,
    .binding_released = on_released,
#if IS_ENABLED(CONFIG_ZMK_BEHAVIOR_METADATA)
    .get_parameter_metadata = zmk_behavior_get_empty_param_metadata,
#endif
};

BEHAVIOR_DT_INST_DEFINE(0, NULL, NULL, NULL, NULL, POST_KERNEL, CONFIG_KERNEL_INIT_PRIORITY_DEFAULT,
                        &drag_lock_api);

static int drag_lock_layer_listener(const zmk_event_t *eh) {
    if (held && !zmk_keymap_layer_active(MOUSE_LAYER)) {
        set_held(false);
    }
    return ZMK_EV_EVENT_BUBBLE;
}

ZMK_LISTENER(frost_drag_lock, drag_lock_layer_listener);
ZMK_SUBSCRIPTION(frost_drag_lock, zmk_layer_state_changed);

#endif
