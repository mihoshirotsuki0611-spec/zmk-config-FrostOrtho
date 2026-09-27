/*
 * スクロールの縦横固定 (Conductor風)
 * ボールを動かし始めた向きで「縦」か「横」を決め、手を止めるまでもう一方の動きを捨てる。
 */
#define DT_DRV_COMPAT zmk_input_processor_scroll_snap

#include <stdlib.h>
#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/input/input.h>
#include <drivers/input_processor.h>

enum snap_axis { AXIS_NONE, AXIS_V, AXIS_H };

struct snap_config {
    int lock_threshold;
    int release_ms;
    int ratio_percent;
};

struct snap_data {
    int64_t last_ms;
    int acc_v;
    int acc_h;
    enum snap_axis axis;
};

static int snap_handle_event(const struct device *dev, struct input_event *event, uint32_t param1,
                             uint32_t param2, struct zmk_input_processor_state *state) {
    const struct snap_config *cfg = dev->config;
    struct snap_data *data = dev->data;

    if (event->type != INPUT_EV_REL ||
        (event->code != INPUT_REL_WHEEL && event->code != INPUT_REL_HWHEEL)) {
        return ZMK_INPUT_PROC_CONTINUE;
    }

    int64_t now = k_uptime_get();
    if (now - data->last_ms > cfg->release_ms) {
        data->axis = AXIS_NONE;
        data->acc_v = 0;
        data->acc_h = 0;
    }
    data->last_ms = now;

    bool vertical = event->code == INPUT_REL_WHEEL;

    if (data->axis == AXIS_NONE) {
        if (vertical) {
            data->acc_v += abs(event->value);
        } else {
            data->acc_h += abs(event->value);
        }
        if (data->acc_v + data->acc_h >= cfg->lock_threshold) {
            data->axis = (data->acc_h * 100 > data->acc_v * cfg->ratio_percent) ? AXIS_H : AXIS_V;
        } else {
            /* 向きが決まるまでは動かさない */
            event->value = 0;
            return ZMK_INPUT_PROC_CONTINUE;
        }
    }

    if ((data->axis == AXIS_V && !vertical) || (data->axis == AXIS_H && vertical)) {
        /* sync 情報を保つため、イベントは捨てずに値だけ 0 にする */
        event->value = 0;
    }
    return ZMK_INPUT_PROC_CONTINUE;
}

static const struct zmk_input_processor_driver_api snap_api = {
    .handle_event = snap_handle_event,
};

#define SNAP_INST(n)                                                                               \
    static struct snap_data snap_data_##n = {0};                                                   \
    static const struct snap_config snap_config_##n = {                                            \
        .lock_threshold = DT_INST_PROP(n, lock_threshold),                                         \
        .release_ms = DT_INST_PROP(n, release_ms),                                                 \
        .ratio_percent = DT_INST_PROP(n, ratio_percent),                                           \
    };                                                                                             \
    DEVICE_DT_INST_DEFINE(n, NULL, NULL, &snap_data_##n, &snap_config_##n, POST_KERNEL,            \
                          CONFIG_KERNEL_INIT_PRIORITY_DEFAULT, &snap_api);

DT_INST_FOREACH_STATUS_OKAY(SNAP_INST)
