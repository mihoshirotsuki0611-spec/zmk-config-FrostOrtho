/*
 * マウスモード(AML)の入口の関所
 * マウスモードでないときは、ボールがはっきり転がるまで動きを捨てる。
 * 指が触れた程度の小さな動きでマウスモードに戻ってしまうのを防ぐ(カーソルも動かさない)。
 * キーでマウスモードを抜けたら、関所はすぐ閉じる。
 */
#define DT_DRV_COMPAT zmk_input_processor_aml_gate

#include <stdlib.h>
#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/input/input.h>
#include <drivers/input_processor.h>
#include <zmk/keymap.h>

struct gate_config {
    int layer;
    int threshold;
    int window_ms;
};

struct gate_data {
    int64_t last_ms;
    int acc;
    bool open;
    bool was_active;
};

static int gate_handle_event(const struct device *dev, struct input_event *event, uint32_t param1,
                             uint32_t param2, struct zmk_input_processor_state *state) {
    const struct gate_config *cfg = dev->config;
    struct gate_data *data = dev->data;

    if (event->type != INPUT_EV_REL ||
        (event->code != INPUT_REL_X && event->code != INPUT_REL_Y)) {
        return ZMK_INPUT_PROC_CONTINUE;
    }

    int64_t now = k_uptime_get();
    bool active = zmk_keymap_layer_active(cfg->layer);

    if (active) {
        data->was_active = true;
        data->open = true;
        data->last_ms = now;
        return ZMK_INPUT_PROC_CONTINUE;
    }

    /* キーで抜けた直後、またはしばらく止まっていたら関所を閉じる */
    if (data->was_active || now - data->last_ms > cfg->window_ms) {
        data->was_active = false;
        data->open = false;
        data->acc = 0;
    }
    data->last_ms = now;

    if (data->open) {
        return ZMK_INPUT_PROC_CONTINUE;
    }

    data->acc += abs(event->value);
    if (data->acc >= cfg->threshold) {
        data->open = true;
        return ZMK_INPUT_PROC_CONTINUE;
    }
    return ZMK_INPUT_PROC_STOP;
}

static const struct zmk_input_processor_driver_api gate_api = {
    .handle_event = gate_handle_event,
};

#define GATE_INST(n)                                                                               \
    static struct gate_data gate_data_##n = {0};                                                   \
    static const struct gate_config gate_config_##n = {                                            \
        .layer = DT_INST_PROP(n, layer),                                                           \
        .threshold = DT_INST_PROP(n, threshold),                                                   \
        .window_ms = DT_INST_PROP(n, window_ms),                                                   \
    };                                                                                             \
    DEVICE_DT_INST_DEFINE(n, NULL, NULL, &gate_data_##n, &gate_config_##n, POST_KERNEL,            \
                          CONFIG_KERNEL_INIT_PRIORITY_DEFAULT, &gate_api);

DT_INST_FOREACH_STATUS_OKAY(GATE_INST)
