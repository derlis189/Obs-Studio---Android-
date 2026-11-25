// SPDX-License-Identifier: GPL-2.0-or-later
#include "android-uvc.h"
#include <android/log.h>
#include <jni.h>
#include <linux/usbdevice_fs.h>
#include <sys/ioctl.h>
#include <unistd.h>
#include <errno.h>
#include <time.h>

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavutil/frame.h>
#include <libavutil/imgutils.h>
#include <libswscale/swscale.h>
}

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstring>
#include <mutex>
#include <thread>
#include <vector>

#define UVC_LOGI(...) __android_log_print(ANDROID_LOG_INFO, "ObsUsbUvcNative", __VA_ARGS__)
#define UVC_LOGW(...) __android_log_print(ANDROID_LOG_WARN, "ObsUsbUvcNative", __VA_ARGS__)
#define UVC_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "ObsUsbUvcNative", __VA_ARGS__)

namespace {

constexpr uint8_t USB_DT_INTERFACE = 0x04;
constexpr uint8_t USB_DT_ENDPOINT = 0x05;
constexpr uint8_t USB_DT_CS_INTERFACE = 0x24;
constexpr uint8_t USB_CLASS_VIDEO = 0x0E;
constexpr uint8_t UVC_SUBCLASS_VC = 0x01;
constexpr uint8_t UVC_SUBCLASS_VS = 0x02;
constexpr uint8_t VC_HEADER = 0x01;
constexpr uint8_t VS_FORMAT_UNCOMPRESSED = 0x04;
constexpr uint8_t VS_FRAME_UNCOMPRESSED = 0x05;
constexpr uint8_t VS_FORMAT_MJPEG = 0x06;
constexpr uint8_t VS_FRAME_MJPEG = 0x07;

constexpr uint8_t UVC_SET_CUR = 0x01;
constexpr uint8_t UVC_GET_CUR = 0x81;
constexpr uint16_t VS_PROBE_CONTROL = 0x0100;
constexpr uint16_t VS_COMMIT_CONTROL = 0x0200;

enum class UvcPixFmt { MJPEG, YUY2, NV12 };

struct UvcFrameDesc {
    uint8_t format_index{};
    uint8_t frame_index{};
    UvcPixFmt pix_fmt{UvcPixFmt::MJPEG};
    uint16_t width{};
    uint16_t height{};
    uint32_t frame_interval{};
};

struct UvcAltSetting {
    uint8_t alt_num{};
    uint8_t endpoint{};
    bool is_iso{};
    uint32_t max_packet_size{};
};

struct UvcConfig {
    int vs_interface{-1};
    uint16_t bcd_uvc{0x0100};
    UvcFrameDesc frame{};
    std::vector<UvcAltSetting> alts;
    bool valid{};
};

uint16_t rd16(const uint8_t *p) { return uint16_t(p[0]) | (uint16_t(p[1]) << 8); }
uint32_t rd32(const uint8_t *p) {
    return uint32_t(p[0]) | (uint32_t(p[1]) << 8) | (uint32_t(p[2]) << 16) | (uint32_t(p[3]) << 24);
}
void wr16(uint8_t *p, uint16_t v) { p[0] = v & 0xFF; p[1] = (v >> 8) & 0xFF; }
void wr32(uint8_t *p, uint32_t v) {
    p[0] = v & 0xFF; p[1] = (v >> 8) & 0xFF; p[2] = (v >> 16) & 0xFF; p[3] = (v >> 24) & 0xFF;
}
uint64_t now_ns() {
    timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return uint64_t(ts.tv_sec) * 1000000000ULL + uint64_t(ts.tv_nsec);
}

UvcConfig parse_uvc_descriptors(const uint8_t *desc, size_t len, int target_height) {
    UvcConfig cfg{};
    std::vector<UvcFrameDesc> frames;
    int cur_alt = 0;
    bool in_vc = false;
    bool in_vs = false;
    uint8_t cur_fmt_idx = 0;
    UvcPixFmt cur_pix_fmt = UvcPixFmt::MJPEG;

    size_t pos = 0;
    while (pos + 2 <= len) {
        uint8_t bLen = desc[pos];
        uint8_t bType = desc[pos + 1];
        if (bLen < 2 || pos + bLen > len) break;

        if (bType == USB_DT_INTERFACE && bLen >= 9) {
            uint8_t if_num = desc[pos + 2];
            uint8_t alt_num = desc[pos + 3];
            uint8_t cls = desc[pos + 5];
            uint8_t sub = desc[pos + 6];
            in_vc = (cls == USB_CLASS_VIDEO && sub == UVC_SUBCLASS_VC);
            if (cls == USB_CLASS_VIDEO && sub == UVC_SUBCLASS_VS) {
                if (cfg.vs_interface < 0) cfg.vs_interface = if_num;
                in_vs = (if_num == cfg.vs_interface);
                cur_alt = alt_num;
            } else {
                in_vs = false;
            }
        } else if (in_vc && bType == USB_DT_CS_INTERFACE && bLen >= 5 && desc[pos + 2] == VC_HEADER) {
            cfg.bcd_uvc = rd16(desc + pos + 3);
        } else if (in_vs && bType == USB_DT_CS_INTERFACE && bLen >= 4) {
            uint8_t subtype = desc[pos + 2];
            if (subtype == VS_FORMAT_MJPEG && bLen >= 6) {
                cur_fmt_idx = desc[pos + 3];
                cur_pix_fmt = UvcPixFmt::MJPEG;
            } else if (subtype == VS_FORMAT_UNCOMPRESSED && bLen >= 21) {
                const bool is_yuy2 = (desc[pos + 5] == 'Y' && desc[pos + 6] == 'U' &&
                                      desc[pos + 7] == 'Y' && desc[pos + 8] == '2');
                const bool is_nv12 = (desc[pos + 5] == 'N' && desc[pos + 6] == 'V' &&
                                      desc[pos + 7] == '1' && desc[pos + 8] == '2');
                if (is_yuy2) { cur_fmt_idx = desc[pos + 3]; cur_pix_fmt = UvcPixFmt::YUY2; }
                else if (is_nv12) { cur_fmt_idx = desc[pos + 3]; cur_pix_fmt = UvcPixFmt::NV12; }
                else cur_fmt_idx = 0;
            } else if ((subtype == VS_FRAME_MJPEG || subtype == VS_FRAME_UNCOMPRESSED) && bLen >= 26 && cur_fmt_idx > 0) {
                UvcFrameDesc f{};
                f.format_index = cur_fmt_idx;
                f.frame_index = desc[pos + 3];
                f.pix_fmt = (subtype == VS_FRAME_MJPEG) ? UvcPixFmt::MJPEG : cur_pix_fmt;
                f.width = rd16(desc + pos + 5);
                f.height = rd16(desc + pos + 7);
                f.frame_interval = rd32(desc + pos + 21);
                if (f.frame_interval == 0) f.frame_interval = 333333;
                if (f.width >= 320 && f.height >= 240 && f.width <= 1920 && f.height <= 1080) {
                    frames.push_back(f);
                }
            }
        } else if (in_vs && bType == USB_DT_ENDPOINT && bLen >= 7) {
            uint8_t ep = desc[pos + 2];
            uint8_t attr = desc[pos + 3] & 0x03;
            uint16_t raw_mps = rd16(desc + pos + 4);
            uint32_t mps = (raw_mps & 0x07FFu) * (1u + ((raw_mps >> 11) & 0x03u));
            if ((ep & 0x80) && (attr == 1 || attr == 2) && mps > 0) {
                cfg.alts.push_back(UvcAltSetting{uint8_t(cur_alt), ep, attr == 1, mps});
            }
        }
        pos += bLen;
    }

    if (cfg.vs_interface < 0 || frames.empty() || cfg.alts.empty()) {
        UVC_LOGW("parse_uvc_descriptors incomplete: vs_if=%d frames=%zu alts=%zu", cfg.vs_interface, frames.size(), cfg.alts.size());
        return cfg;
    }

    long long tgt_h = (target_height >= 480 && target_height <= 1080) ? target_height : 720;
    long long tgt_w = (tgt_h == 480) ? 640 : (tgt_h == 1080 ? 1920 : 1280);
    auto score_frame = [&](const UvcFrameDesc &f) -> long long {
        long long diff = std::llabs(long(f.width) * f.height - tgt_w * tgt_h);
        long long aspect = std::llabs(long(f.width) * 9LL - long(f.height) * 16LL) * 400LL;
        long long fmt_bonus = (f.pix_fmt == UvcPixFmt::MJPEG) ? 0LL : 60000LL;
        return diff + aspect + fmt_bonus;
    };
    cfg.frame = *std::min_element(frames.begin(), frames.end(),
                                  [&](const UvcFrameDesc &a, const UvcFrameDesc &b) { return score_frame(a) < score_frame(b); });

    // Sort alt-settings ascending by max_packet_size so we pick the smallest sufficient bandwidth (prevents ENOSPC)
    std::sort(cfg.alts.begin(), cfg.alts.end(), [](const UvcAltSetting &a, const UvcAltSetting &b) {
        if (a.is_iso != b.is_iso) return a.is_iso;
        return a.max_packet_size < b.max_packet_size;
    });
    cfg.valid = true;
    UVC_LOGI("Parsed UVC bcd=0x%04x if=%d alts=%zu chosen fmt=%u(%d) frame=%u (%ux%u interval=%u)",
             cfg.bcd_uvc, cfg.vs_interface, cfg.alts.size(), cfg.frame.format_index, int(cfg.frame.pix_fmt),
             cfg.frame.frame_index, cfg.frame.width, cfg.frame.height, cfg.frame.frame_interval);
    return cfg;
}

bool uvc_ctrl(int fd, uint8_t req_type, uint8_t req, uint16_t val, uint16_t idx, uint8_t *buf, uint16_t len) {
    usbdevfs_ctrltransfer ctrl{};
    ctrl.bRequestType = req_type;
    ctrl.bRequest = req;
    ctrl.wValue = val;
    ctrl.wIndex = idx;
    ctrl.wLength = len;
    ctrl.timeout = 1000;
    ctrl.data = buf;
    return ioctl(fd, USBDEVFS_CONTROL, &ctrl) >= 0;
}

struct UvcSession {
    std::atomic<bool> running{false};
    std::thread worker;
    int fd{-1};
    UvcConfig cfg{};
    AVCodecContext *mjpeg_ctx{};
    AVFrame *av_frame{};
    AVPacket *av_pkt{};
    SwsContext *sws{};
    std::vector<uint8_t> i420_buf;
    std::vector<uint8_t> jpeg_fix;
    int sws_w{}, sws_h{}, sws_fmt{-1};
    uint64_t decoded_frames{0};

    void deliver_nv12(const uint8_t *nv12, int w, int h, uint64_t ts) {
        if (!sws || sws_w != w || sws_h != h || sws_fmt != AV_PIX_FMT_NV12) {
            if (sws) sws_freeContext(sws);
            sws = sws_getContext(w, h, AV_PIX_FMT_NV12, w, h, AV_PIX_FMT_YUV420P,
                                 SWS_FAST_BILINEAR, nullptr, nullptr, nullptr);
            sws_w = w; sws_h = h; sws_fmt = AV_PIX_FMT_NV12;
            i420_buf.resize(size_t(w) * h * 3 / 2);
        }
        if (!sws) return;
        const uint8_t *src[2] = {nv12, nv12 + size_t(w) * h};
        int src_stride[2] = {w, w};
        uint8_t *dst[3] = {
            i420_buf.data(),
            i420_buf.data() + size_t(w) * h,
            i420_buf.data() + size_t(w) * h * 5 / 4
        };
        int dst_stride[3] = {w, w / 2, w / 2};
        sws_scale(sws, src, src_stride, 0, h, dst, dst_stride);
        android_uvc_deliver_i420(dst[0], dst[1], dst[2], uint32_t(w), uint32_t(w / 2), uint32_t(w / 2),
                                 uint32_t(w), uint32_t(h), ts);
        if (++decoded_frames == 1 || (decoded_frames % 150) == 0) {
            UVC_LOGI("UVC delivered NV12 frame #%llu (%dx%d)", (unsigned long long)decoded_frames, w, h);
        }
    }

    void decode_and_deliver(const std::vector<uint8_t> &frame_data) {
        if (frame_data.size() < 16) return;
        uint64_t ts = now_ns();
        if (cfg.frame.pix_fmt == UvcPixFmt::YUY2) {
            size_t expected = size_t(cfg.frame.width) * cfg.frame.height * 2;
            if (frame_data.size() >= expected) {
                android_uvc_deliver_yuy2(frame_data.data(), cfg.frame.width, cfg.frame.height, ts);
                if (++decoded_frames == 1 || (decoded_frames % 150) == 0) {
                    UVC_LOGI("UVC delivered YUY2 frame #%llu (%ux%u)", (unsigned long long)decoded_frames, cfg.frame.width, cfg.frame.height);
                }
            }
            return;
        }
        if (cfg.frame.pix_fmt == UvcPixFmt::NV12) {
            size_t expected = size_t(cfg.frame.width) * cfg.frame.height * 3 / 2;
            if (frame_data.size() >= expected) {
                deliver_nv12(frame_data.data(), cfg.frame.width, cfg.frame.height, ts);
            }
            return;
        }

        // MJPEG: locate JPEG SOI (0xFF 0xD8) and ensure EOI (0xFF 0xD9)
        size_t soi = 0;
        bool found_soi = false;
        size_t search_lim = std::min<size_t>(frame_data.size() - 1, 512);
        for (size_t i = 0; i < search_lim; ++i) {
            if (frame_data[i] == 0xFF && frame_data[i + 1] == 0xD8) { soi = i; found_soi = true; break; }
        }
        if (!found_soi) return;
        const uint8_t *jpg_ptr = frame_data.data() + soi;
        size_t jpg_len = frame_data.size() - soi;
        if (jpg_len < 64) return;
        if (!(jpg_ptr[jpg_len - 2] == 0xFF && jpg_ptr[jpg_len - 1] == 0xD9)) {
            jpeg_fix.assign(jpg_ptr, jpg_ptr + jpg_len);
            jpeg_fix.push_back(0xFF);
            jpeg_fix.push_back(0xD9);
            jpg_ptr = jpeg_fix.data();
            jpg_len = jpeg_fix.size();
        }

        if (!mjpeg_ctx) {
            const AVCodec *codec = avcodec_find_decoder(AV_CODEC_ID_MJPEG);
            if (!codec) return;
            mjpeg_ctx = avcodec_alloc_context3(codec);
            if (!mjpeg_ctx) return;
            mjpeg_ctx->flags |= AV_CODEC_FLAG_LOW_DELAY;
            if (avcodec_open2(mjpeg_ctx, codec, nullptr) < 0) {
                avcodec_free_context(&mjpeg_ctx);
                return;
            }
            av_frame = av_frame_alloc();
            av_pkt = av_packet_alloc();
        }
        av_pkt->data = const_cast<uint8_t *>(jpg_ptr);
        av_pkt->size = int(jpg_len);
        if (avcodec_send_packet(mjpeg_ctx, av_pkt) < 0) return;
        while (avcodec_receive_frame(mjpeg_ctx, av_frame) == 0) {
            int w = av_frame->width, h = av_frame->height;
            if (w <= 0 || h <= 0) continue;
            if (av_frame->format == AV_PIX_FMT_YUV420P || av_frame->format == AV_PIX_FMT_YUVJ420P) {
                android_uvc_deliver_i420(av_frame->data[0], av_frame->data[1], av_frame->data[2],
                                         uint32_t(av_frame->linesize[0]), uint32_t(av_frame->linesize[1]),
                                         uint32_t(av_frame->linesize[2]), uint32_t(w), uint32_t(h), ts);
            } else {
                if (!sws || sws_w != w || sws_h != h || sws_fmt != av_frame->format) {
                    if (sws) sws_freeContext(sws);
                    sws = sws_getContext(w, h, AVPixelFormat(av_frame->format), w, h, AV_PIX_FMT_YUV420P,
                                         SWS_FAST_BILINEAR, nullptr, nullptr, nullptr);
                    sws_w = w; sws_h = h; sws_fmt = av_frame->format;
                    i420_buf.resize(size_t(w) * h * 3 / 2);
                }
                if (sws) {
                    uint8_t *dst[3] = {
                        i420_buf.data(),
                        i420_buf.data() + size_t(w) * h,
                        i420_buf.data() + size_t(w) * h * 5 / 4
                    };
                    int dst_stride[3] = {w, w / 2, w / 2};
                    sws_scale(sws, av_frame->data, av_frame->linesize, 0, h, dst, dst_stride);
                    android_uvc_deliver_i420(dst[0], dst[1], dst[2], uint32_t(w), uint32_t(w / 2), uint32_t(w / 2),
                                             uint32_t(w), uint32_t(h), ts);
                }
            }
            if (++decoded_frames == 1 || (decoded_frames % 150) == 0) {
                UVC_LOGI("UVC delivered MJPEG frame #%llu (%dx%d fmt=%d)", (unsigned long long)decoded_frames, w, h, av_frame->format);
            }
        }
    }

    void process_payload(const uint8_t *pkt, size_t len, std::vector<uint8_t> &accum, int &last_fid, bool is_bulk) {
        if (len < 2) return;
        uint8_t hdr_len = pkt[0];
        uint8_t bm = pkt[1];
        bool looks_like_hdr = (hdr_len >= 2 && hdr_len <= 12 && hdr_len <= len && ((bm & 0x80) != 0 || hdr_len == 2));
        if (is_bulk && !looks_like_hdr && !accum.empty()) {
            // Raw bulk continuation chunk without UVC header
            constexpr size_t kMaxFrameBytes = 6 * 1024 * 1024;
            if (accum.size() + len <= kMaxFrameBytes) accum.insert(accum.end(), pkt, pkt + len);
            return;
        }
        if (hdr_len < 2 || hdr_len > len) return;
        if (bm & 0x40) {
            accum.clear();
            return;
        }
        int fid = bm & 0x01;
        bool eof = (bm & 0x02) != 0;
        if (last_fid >= 0 && fid != last_fid && !accum.empty()) {
            decode_and_deliver(accum);
            accum.clear();
        }
        last_fid = fid;
        if (len > hdr_len) {
            constexpr size_t kMaxFrameBytes = 6 * 1024 * 1024;
            if (accum.size() + (len - hdr_len) <= kMaxFrameBytes) {
                accum.insert(accum.end(), pkt + hdr_len, pkt + len);
            } else {
                accum.clear();
            }
        }
        if (eof && !accum.empty()) {
            decode_and_deliver(accum);
            accum.clear();
        }
    }

    void run_loop() {
        // Probe/Commit: try 34 bytes for UVC 1.1+, fallback to 26 bytes
        uint8_t probe[34]{};
        uint16_t probe_len = (cfg.bcd_uvc >= 0x0110) ? 34 : 26;
        auto fill_probe = [&]() {
            std::memset(probe, 0, sizeof(probe));
            wr16(probe + 0, 0x0001); // bmHint: dwFrameInterval
            probe[2] = cfg.frame.format_index;
            probe[3] = cfg.frame.frame_index;
            wr32(probe + 4, cfg.frame.frame_interval);
        };
        fill_probe();
        if (!uvc_ctrl(fd, 0x21, UVC_SET_CUR, VS_PROBE_CONTROL, uint16_t(cfg.vs_interface), probe, probe_len)) {
            probe_len = (probe_len == 34) ? 26 : 34;
            fill_probe();
            uvc_ctrl(fd, 0x21, UVC_SET_CUR, VS_PROBE_CONTROL, uint16_t(cfg.vs_interface), probe, probe_len);
        }
        uvc_ctrl(fd, 0xA1, UVC_GET_CUR, VS_PROBE_CONTROL, uint16_t(cfg.vs_interface), probe, probe_len);
        uint32_t max_payload = rd32(probe + 22);
        if (!uvc_ctrl(fd, 0x21, UVC_SET_CUR, VS_COMMIT_CONTROL, uint16_t(cfg.vs_interface), probe, probe_len)) {
            UVC_LOGE("UVC VS_COMMIT_CONTROL failed (len=%u): errno=%d", probe_len, errno);
            return;
        }
        UVC_LOGI("UVC committed (len=%u) dwMaxPayloadTransferSize=%u", probe_len, max_payload);

        // Choose smallest ISO alt-setting >= max_payload (or largest ISO <= 3072 if max_payload==0), fallback on ENOSPC
        bool has_iso = false;
        for (const auto &a : cfg.alts) if (a.is_iso) { has_iso = true; break; }

        std::vector<uint8_t> accum;
        accum.reserve(cfg.frame.pix_fmt == UvcPixFmt::MJPEG ? 256 * 1024 : size_t(cfg.frame.width) * cfg.frame.height * 2);
        int last_fid = -1;

        if (has_iso) {
            std::vector<UvcAltSetting> iso_alts;
            for (const auto &a : cfg.alts) if (a.is_iso) iso_alts.push_back(a);
            int chosen_idx = int(iso_alts.size()) - 1;
            if (max_payload > 0) {
                for (int i = 0; i < int(iso_alts.size()); ++i) {
                    if (iso_alts[i].max_packet_size >= max_payload) { chosen_idx = i; break; }
                }
            }

            constexpr int kNumUrbs = 8;
            constexpr int kPacketsPerUrb = 16;
            std::vector<std::vector<uint8_t>> buffers;
            std::vector<std::vector<uint8_t>> urb_mem;
            UvcAltSetting active_alt{};
            int submitted = 0;

            // Try chosen alt-setting; if kernel USB host rejects with ENOSPC, step down automatically
            for (int try_idx = chosen_idx; try_idx >= 0 && submitted == 0; --try_idx) {
                active_alt = iso_alts[try_idx];
                usbdevfs_setinterface set_if{unsigned(cfg.vs_interface), unsigned(active_alt.alt_num)};
                if (ioctl(fd, USBDEVFS_SETINTERFACE, &set_if) < 0) {
                    UVC_LOGW("SETINTERFACE alt=%u failed errno=%d", active_alt.alt_num, errno);
                    continue;
                }
                const size_t pkt_sz = active_alt.max_packet_size;
                const size_t urb_buf_sz = pkt_sz * kPacketsPerUrb;
                const size_t urb_struct_sz = sizeof(usbdevfs_urb) + kPacketsPerUrb * sizeof(usbdevfs_iso_packet_desc);
                buffers.assign(kNumUrbs, std::vector<uint8_t>(urb_buf_sz));
                urb_mem.assign(kNumUrbs, std::vector<uint8_t>(urb_struct_sz));
                submitted = 0;
                for (int i = 0; i < kNumUrbs; ++i) {
                    auto *u = reinterpret_cast<usbdevfs_urb *>(urb_mem[i].data());
                    std::memset(u, 0, urb_struct_sz);
                    u->type = USBDEVFS_URB_TYPE_ISO;
                    u->endpoint = active_alt.endpoint;
                    u->flags = USBDEVFS_URB_ISO_ASAP;
                    u->buffer = buffers[i].data();
                    u->buffer_length = int(urb_buf_sz);
                    u->number_of_packets = kPacketsPerUrb;
                    u->usercontext = reinterpret_cast<void *>(uintptr_t(i));
                    for (int p = 0; p < kPacketsPerUrb; ++p) u->iso_frame_desc[p].length = unsigned(pkt_sz);
                    if (ioctl(fd, USBDEVFS_SUBMITURB, u) == 0) {
                        submitted++;
                    } else if (errno == ENOSPC) {
                        UVC_LOGW("SUBMITURB ENOSPC on alt=%u mps=%u, stepping down", active_alt.alt_num, active_alt.max_packet_size);
                        for (int j = 0; j < i; ++j) ioctl(fd, USBDEVFS_DISCARDURB, urb_mem[j].data());
                        submitted = 0;
                        break;
                    }
                }
            }
            UVC_LOGI("UVC ISO streaming active: alt=%u ep=0x%02x submitted=%d/%d mps=%u",
                     active_alt.alt_num, active_alt.endpoint, submitted, kNumUrbs, active_alt.max_packet_size);
            const size_t pkt_sz = active_alt.max_packet_size;
            while (running.load() && submitted > 0) {
                usbdevfs_urb *reaped = nullptr;
                if (ioctl(fd, USBDEVFS_REAPURB, &reaped) < 0) {
                    if (errno == EINTR) continue;
                    if (errno == ENODEV || errno == EBADF) break;
                    continue;
                }
                if (!reaped) continue;
                auto *base = static_cast<const uint8_t *>(reaped->buffer);
                for (int p = 0; p < reaped->number_of_packets; ++p) {
                    const auto &d = reaped->iso_frame_desc[p];
                    if (d.status == 0 && d.actual_length > 0) {
                        process_payload(base + size_t(p) * pkt_sz, d.actual_length, accum, last_fid, false);
                    }
                }
                if (running.load()) {
                    for (int p = 0; p < reaped->number_of_packets; ++p) {
                        reaped->iso_frame_desc[p].length = unsigned(pkt_sz);
                        reaped->iso_frame_desc[p].actual_length = 0;
                        reaped->iso_frame_desc[p].status = 0;
                    }
                    if (ioctl(fd, USBDEVFS_SUBMITURB, reaped) < 0) submitted--;
                } else {
                    submitted--;
                }
            }
            for (size_t i = 0; i < urb_mem.size(); ++i) {
                ioctl(fd, USBDEVFS_DISCARDURB, urb_mem[i].data());
            }
        } else {
            const auto &bulk_alt = cfg.alts.front();
            usbdevfs_setinterface set_if{unsigned(cfg.vs_interface), unsigned(bulk_alt.alt_num)};
            ioctl(fd, USBDEVFS_SETINTERFACE, &set_if);
            size_t xfer_sz = (max_payload >= 16384 && max_payload <= 524288) ? max_payload : 65536;
            std::vector<uint8_t> bulk_buf(xfer_sz);
            UVC_LOGI("UVC BULK streaming active: ep=0x%02x xfer_sz=%zu", bulk_alt.endpoint, xfer_sz);
            while (running.load()) {
                usbdevfs_bulktransfer bt{};
                bt.ep = bulk_alt.endpoint;
                bt.len = unsigned(bulk_buf.size());
                bt.timeout = 500;
                bt.data = bulk_buf.data();
                int n = ioctl(fd, USBDEVFS_BULK, &bt);
                if (n > 0) process_payload(bulk_buf.data(), size_t(n), accum, last_fid, true);
                else if (n < 0 && (errno == ENODEV || errno == EBADF)) break;
            }
        }

        usbdevfs_setinterface reset_if{unsigned(cfg.vs_interface), 0};
        ioctl(fd, USBDEVFS_SETINTERFACE, &reset_if);

        if (sws) { sws_freeContext(sws); sws = nullptr; }
        if (av_frame) { av_frame_free(&av_frame); }
        if (av_pkt) { av_packet_free(&av_pkt); }
        if (mjpeg_ctx) { avcodec_free_context(&mjpeg_ctx); }
    }
};

std::mutex g_uvc_mutex;
UvcSession *g_uvc_session = nullptr;

} // namespace

void android_uvc_stop_all() {
    UvcSession *old = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_uvc_mutex);
        old = g_uvc_session;
        g_uvc_session = nullptr;
    }
    if (old) {
        old->running.store(false);
        if (old->worker.joinable()) old->worker.join();
        delete old;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_obsandroid_port_NativeBridge_startUvcCapture(JNIEnv *env, jclass, jint fd, jbyteArray rawDescriptors, jint targetHeight) {
    android_uvc_stop_all();
    if (fd < 0 || !rawDescriptors) return JNI_FALSE;
    jsize len = env->GetArrayLength(rawDescriptors);
    if (len <= 0) return JNI_FALSE;
    std::vector<uint8_t> desc(static_cast<size_t>(len));
    env->GetByteArrayRegion(rawDescriptors, 0, len, reinterpret_cast<jbyte *>(desc.data()));

    UvcConfig cfg = parse_uvc_descriptors(desc.data(), desc.size(), int(targetHeight));
    if (!cfg.valid) return JNI_FALSE;

    auto *sess = new UvcSession();
    sess->fd = fd;
    sess->cfg = cfg;
    sess->running.store(true);
    sess->worker = std::thread([sess]() { sess->run_loop(); });
    {
        std::lock_guard<std::mutex> lock(g_uvc_mutex);
        g_uvc_session = sess;
    }
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_org_obsandroid_port_NativeBridge_stopUvcCapture(JNIEnv *, jclass) {
    android_uvc_stop_all();
}
