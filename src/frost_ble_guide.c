/*
 * DeX(Android)版レイヤーガイド用: ガイドに送る情報(レイヤー・キー押下・電池・状態)を
 * Bluetooth の独自サービスでも通知する。中身は Mac 版(Raw HID)と同じパケット。
 */
#include <zephyr/kernel.h>
#include <zephyr/bluetooth/gatt.h>
#include <zephyr/bluetooth/uuid.h>
#include <string.h>
#include <zmk/event_manager.h>
#include <raw_hid/events.h>

#define PKT_LEN 20 /* BLE の標準MTUで1回に送れる大きさ */

/* サービス: 6f5a0001-3c1d-4e8a-9b2e-0f7a1c2d3e4f / 通知: 6f5a0002-... */
static struct bt_uuid_128 guide_svc_uuid =
    BT_UUID_INIT_128(BT_UUID_128_ENCODE(0x6f5a0001, 0x3c1d, 0x4e8a, 0x9b2e, 0x0f7a1c2d3e4f));
static struct bt_uuid_128 guide_chr_uuid =
    BT_UUID_INIT_128(BT_UUID_128_ENCODE(0x6f5a0002, 0x3c1d, 0x4e8a, 0x9b2e, 0x0f7a1c2d3e4f));

static bool notify_on;

static void ccc_changed(const struct bt_gatt_attr *attr, uint16_t value) {
    notify_on = (value == BT_GATT_CCC_NOTIFY);
}

BT_GATT_SERVICE_DEFINE(frost_guide_svc, BT_GATT_PRIMARY_SERVICE(&guide_svc_uuid),
                       BT_GATT_CHARACTERISTIC(&guide_chr_uuid.uuid, BT_GATT_CHRC_NOTIFY,
                                              BT_GATT_PERM_NONE, NULL, NULL, NULL),
                       BT_GATT_CCC(ccc_changed, BT_GATT_PERM_READ | BT_GATT_PERM_WRITE));

K_MSGQ_DEFINE(guide_q, PKT_LEN, 16, 4);

static void guide_work_cb(struct k_work *work) {
    uint8_t pkt[PKT_LEN];
    while (k_msgq_get(&guide_q, pkt, K_NO_WAIT) == 0) {
        if (notify_on) {
            bt_gatt_notify(NULL, &frost_guide_svc.attrs[1], pkt, sizeof(pkt));
        }
    }
}
static K_WORK_DEFINE(guide_work, guide_work_cb);

static int guide_listener(const zmk_event_t *eh) {
    const struct raw_hid_sent_event *ev = as_raw_hid_sent_event(eh);
    if (ev && notify_on) {
        uint8_t pkt[PKT_LEN] = {0};
        memcpy(pkt, ev->data, MIN(ev->length, PKT_LEN));
        if (k_msgq_put(&guide_q, pkt, K_NO_WAIT) != 0) {
            k_msgq_purge(&guide_q); /* 詰まったら古いものを捨てる */
            k_msgq_put(&guide_q, pkt, K_NO_WAIT);
        }
        k_work_submit(&guide_work);
    }
    return ZMK_EV_EVENT_BUBBLE;
}
ZMK_LISTENER(frost_ble_guide, guide_listener);
ZMK_SUBSCRIPTION(frost_ble_guide, raw_hid_sent_event);
