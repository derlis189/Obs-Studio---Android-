// SPDX-License-Identifier: GPL-2.0-or-later
#include "android-srt.h"
#include <obs-module.h>
#include <util/platform.h>
#include <android/log.h>
#include <srt/srt.h>

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/avutil.h>
#include <libavutil/channel_layout.h>
#include <libavutil/imgutils.h>
#include <libavutil/opt.h>
#include <libswresample/swresample.h>
#include <libswscale/swscale.h>
}

#include <arpa/inet.h>
#include <netdb.h>
#include <sys/socket.h>
#include <unistd.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <deque>
#include <mutex>
#include <sstream>
#include <string>
#include <thread>
#include <vector>

extern "C" bool obs_android_list_srt_local_urls(int port, int latency_ms, char *out_buf, size_t out_cap);

namespace {
constexpr const char *kTag = "ObsAndroidSrt";
constexpr int kSrtChunkSize = 1316; // 7 * 188-byte MPEG-TS packets

std::once_flag g_srt_init_once;
bool g_srt_registered = false;

void EnsureSrtStarted()
{
    std::call_once(g_srt_init_once, []() {
        srt_startup();
        srt_setloglevel(srt_logging::LogLevel::error);
    });
}

struct SrtEndpoint {
    std::string mode{"caller"};
    std::string host;
    int port{9999};
    int latency_ms{250};
    std::string streamid;
    std::string passphrase;
    int pbkeylen{16};
};

std::string UrlDecode(const std::string &in)
{
    std::string out;
    out.reserve(in.size());
    for (size_t i = 0; i < in.size(); ++i) {
        if (in[i] == '%' && i + 2 < in.size()) {
            char hex[3] = {in[i + 1], in[i + 2], 0};
            char *end = nullptr;
            long val = std::strtol(hex, &end, 16);
            if (end == hex + 2) {
                out.push_back(static_cast<char>(val));
                i += 2;
                continue;
            }
        } else if (in[i] == '+') {
            out.push_back(' ');
            continue;
        }
        out.push_back(in[i]);
    }
    return out;
}

SrtEndpoint ParseSrtConfig(const std::string &rawUrl, const std::string &fallbackMode, int fallbackPort,
                           int fallbackLatencyMs, const std::string &fallbackStreamId,
                           const std::string &fallbackPassphrase)
{
    SrtEndpoint ep;
    ep.mode = (fallbackMode == "listener") ? "listener" : "caller";
    ep.port = std::clamp(fallbackPort > 0 ? fallbackPort : 9999, 1024, 65535);
    ep.latency_ms = std::clamp(fallbackLatencyMs > 0 ? fallbackLatencyMs : 250, 20, 4000);
    ep.streamid = fallbackStreamId;
    ep.passphrase = fallbackPassphrase;

    std::string s = rawUrl;
    while (!s.empty() && (s.front() == ' ' || s.front() == '\t')) s.erase(s.begin());
    while (!s.empty() && (s.back() == ' ' || s.back() == '\t')) s.pop_back();

    if (s.rfind("srt://", 0) == 0 || s.rfind("SRT://", 0) == 0) {
        s = s.substr(6);
    }
    std::string query;
    size_t qpos = s.find('?');
    if (qpos != std::string::npos) {
        query = s.substr(qpos + 1);
        s = s.substr(0, qpos);
    }
    size_t slash = s.find('/');
    if (slash != std::string::npos) {
        s = s.substr(0, slash);
    }

    if (!s.empty()) {
        size_t colon = s.rfind(':');
        if (colon != std::string::npos && colon + 1 < s.size()) {
            std::string pstr = s.substr(colon + 1);
            int parsedPort = std::atoi(pstr.c_str());
            if (parsedPort >= 1 && parsedPort <= 65535) {
                ep.port = parsedPort;
                s = s.substr(0, colon);
            }
        }
        ep.host = s;
    }

    // Parse query parameters (mode, latency, rcvlatency, streamid, passphrase, pbkeylen)
    size_t pos = 0;
    while (pos < query.size()) {
        size_t amp = query.find('&', pos);
        std::string kv = (amp == std::string::npos) ? query.substr(pos) : query.substr(pos, amp - pos);
        pos = (amp == std::string::npos) ? query.size() : amp + 1;
        size_t eq = kv.find('=');
        if (eq == std::string::npos) continue;
        std::string k = kv.substr(0, eq);
        std::string v = UrlDecode(kv.substr(eq + 1));
        for (char &c : k) c = char(std::tolower(static_cast<unsigned char>(c)));
        if (k == "mode") {
            if (v == "listener" || v == "caller") ep.mode = v;
        } else if (k == "latency" || k == "rcvlatency") {
            int lv = std::atoi(v.c_str());
            // FFmpeg/OBS URLs pass latency in microseconds (e.g. 120000); SRT native values <= 10000 are ms
            if (lv > 10000) lv /= 1000;
            if (lv >= 20) ep.latency_ms = std::clamp(lv, 20, 4000);
        } else if (k == "streamid") {
            if (!v.empty()) ep.streamid = v;
        } else if (k == "passphrase") {
            if (!v.empty()) ep.passphrase = v;
        } else if (k == "pbkeylen") {
            int kl = std::atoi(v.c_str());
            if (kl == 16 || kl == 24 || kl == 32) ep.pbkeylen = kl;
        }
    }
    return ep;
}

bool ApplyCommonSrtOptions(SRTSOCKET sock, const SrtEndpoint &ep, bool sender)
{
    int transtype = SRTT_LIVE;
    srt_setsockflag(sock, SRTO_TRANSTYPE, &transtype, sizeof(transtype));

    int lat = ep.latency_ms;
    srt_setsockflag(sock, SRTO_LATENCY, &lat, sizeof(lat));
    srt_setsockflag(sock, SRTO_RCVLATENCY, &lat, sizeof(lat));
    srt_setsockflag(sock, SRTO_PEERLATENCY, &lat, sizeof(lat));

    int tlpktdrop = 1;
    srt_setsockflag(sock, SRTO_TLPKTDROP, &tlpktdrop, sizeof(tlpktdrop));
    int tsbpd = 1;
    srt_setsockflag(sock, SRTO_TSBPDMODE, &tsbpd, sizeof(tsbpd));
    int nak = 1;
    srt_setsockflag(sock, SRTO_NAKREPORT, &nak, sizeof(nak));

    int payload = kSrtChunkSize;
    srt_setsockflag(sock, SRTO_PAYLOADSIZE, &payload, sizeof(payload));

    int connTimeout = 5000;
    srt_setsockflag(sock, SRTO_CONNTIMEO, &connTimeout, sizeof(connTimeout));

    int ioTimeout = 800;
    srt_setsockflag(sock, SRTO_RCVTIMEO, &ioTimeout, sizeof(ioTimeout));
    srt_setsockflag(sock, SRTO_SNDTIMEO, &ioTimeout, sizeof(ioTimeout));

    int sndFlag = sender ? 1 : 0;
    srt_setsockflag(sock, SRTO_SENDER, &sndFlag, sizeof(sndFlag));

    if (!ep.streamid.empty()) {
        srt_setsockflag(sock, SRTO_STREAMID, ep.streamid.data(), int(ep.streamid.size()));
    }
    if (!ep.passphrase.empty()) {
        if (ep.passphrase.size() < 10 || ep.passphrase.size() > 79) return false;
        if (srt_setsockflag(sock, SRTO_PASSPHRASE, ep.passphrase.data(),
                            int(ep.passphrase.size())) == SRT_ERROR) return false;
        int kl = ep.pbkeylen;
        if (srt_setsockflag(sock, SRTO_PBKEYLEN, &kl, sizeof(kl)) == SRT_ERROR) return false;
    }
    return true;
}

SRTSOCKET OpenSrtSocket(const SrtEndpoint &ep, bool sender, std::atomic<bool> &running,
                        std::string *outPeer, std::string *outErr)
{
    EnsureSrtStarted();
    SRTSOCKET sock = srt_create_socket();
    if (sock == SRT_INVALID_SOCK) {
        if (outErr) *outErr = srt_getlasterror_str();
        return SRT_INVALID_SOCK;
    }
    if (!ApplyCommonSrtOptions(sock, ep, sender)) {
        if (outErr) *outErr = "Configuracion AES invalida (contrasena de 10 a 79 bytes)";
        srt_close(sock);
        return SRT_INVALID_SOCK;
    }

    if (ep.mode == "listener") {
        sockaddr_in sa{};
        sa.sin_family = AF_INET;
        sa.sin_port = htons(uint16_t(ep.port));
        sa.sin_addr.s_addr = INADDR_ANY;
        int reuse = 1;
        srt_setsockflag(sock, SRTO_REUSEADDR, &reuse, sizeof(reuse));
        if (srt_bind(sock, reinterpret_cast<sockaddr *>(&sa), sizeof(sa)) == SRT_ERROR) {
            if (outErr) *outErr = std::string("bind:") + srt_getlasterror_str();
            srt_close(sock);
            return SRT_INVALID_SOCK;
        }
        if (srt_listen(sock, 2) == SRT_ERROR) {
            if (outErr) *outErr = std::string("listen:") + srt_getlasterror_str();
            srt_close(sock);
            return SRT_INVALID_SOCK;
        }
        // Wait for peer in short slices so stop() returns promptly
        int nonblock = 0;
        srt_setsockflag(sock, SRTO_RCVSYN, &nonblock, sizeof(nonblock));
        while (running.load()) {
            sockaddr_in peer{};
            int plen = sizeof(peer);
            SRTSOCKET client = srt_accept(sock, reinterpret_cast<sockaddr *>(&peer), &plen);
            if (client != SRT_INVALID_SOCK) {
                int syn = 1;
                srt_setsockflag(client, SRTO_RCVSYN, &syn, sizeof(syn));
                srt_setsockflag(client, SRTO_SNDSYN, &syn, sizeof(syn));
                int ioTimeout = 800;
                srt_setsockflag(client, SRTO_RCVTIMEO, &ioTimeout, sizeof(ioTimeout));
                srt_setsockflag(client, SRTO_SNDTIMEO, &ioTimeout, sizeof(ioTimeout));
                if (outPeer) {
                    char ipbuf[64] = {};
                    inet_ntop(AF_INET, &peer.sin_addr, ipbuf, sizeof(ipbuf));
                    *outPeer = std::string(ipbuf) + ":" + std::to_string(ntohs(peer.sin_port));
                }
                srt_close(sock);
                return client;
            }
            std::this_thread::sleep_for(std::chrono::milliseconds(120));
        }
        srt_close(sock);
        return SRT_INVALID_SOCK;
    }

    // Caller mode (works over Wi-Fi AND 4G/5G mobile data)
    if (ep.host.empty()) {
        if (outErr) *outErr = "Host SRT vacio";
        srt_close(sock);
        return SRT_INVALID_SOCK;
    }
    addrinfo hints{};
    hints.ai_family = AF_INET;
    hints.ai_socktype = SOCK_DGRAM;
    addrinfo *res = nullptr;
    std::string portStr = std::to_string(ep.port);
    int gai = getaddrinfo(ep.host.c_str(), portStr.c_str(), &hints, &res);
    if (gai != 0 || !res) {
        if (outErr) *outErr = std::string("DNS:") + gai_strerror(gai);
        srt_close(sock);
        return SRT_INVALID_SOCK;
    }
    int synchronous = 0;
    bool connected = false;
    if (running.load() &&
        srt_setsockflag(sock, SRTO_RCVSYN, &synchronous, sizeof(synchronous)) != SRT_ERROR) {
        int rc = srt_connect(sock, res->ai_addr, int(res->ai_addrlen));
        if (rc != SRT_ERROR) {
            auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(5);
            while (running.load()) {
                const SRT_SOCKSTATUS state = srt_getsockstate(sock);
                if (state == SRTS_CONNECTED) {
                    synchronous = 1;
                    connected = srt_setsockflag(sock, SRTO_RCVSYN, &synchronous,
                                                 sizeof(synchronous)) != SRT_ERROR;
                    break;
                }
                if (state != SRTS_CONNECTING || std::chrono::steady_clock::now() >= deadline) break;
                std::this_thread::sleep_for(std::chrono::milliseconds(50));
            }
        }
    }
    if (!connected) {
        if (outErr) *outErr = "No se pudo establecer la conexion SRT";
        freeaddrinfo(res);
        srt_close(sock);
        return SRT_INVALID_SOCK;
    }
    if (outPeer) {
        char ipbuf[64] = {};
        auto *sin = reinterpret_cast<sockaddr_in *>(res->ai_addr);
        inet_ntop(AF_INET, &sin->sin_addr, ipbuf, sizeof(ipbuf));
        *outPeer = std::string(ipbuf) + ":" + std::to_string(ep.port);
    }
    freeaddrinfo(res);
    return sock;
}

// ==================== 1. FUENTE SRT (OBS INPUT SOURCE) ====================

struct SrtSourceInstance {
    obs_source_t *source{};
    std::mutex cfg_mutex;
    SrtEndpoint ep;
    bool auto_reconnect{true};
    bool enable_audio{true};
    std::atomic<bool> running{false};
    std::atomic<SRTSOCKET> active_sock{SRT_INVALID_SOCK};
    std::thread worker;
};

struct SrtAvioReadCtx {
    SrtSourceInstance *inst;
    SRTSOCKET sock;
};

int SrtAvioReadPacket(void *opaque, uint8_t *buf, int buf_size)
{
    auto *ctx = static_cast<SrtAvioReadCtx *>(opaque);
    if (!ctx || !ctx->inst || !ctx->inst->running.load() || ctx->sock == SRT_INVALID_SOCK)
        return AVERROR_EOF;
    while (ctx->inst->running.load()) {
        int r = srt_recvmsg(ctx->sock, reinterpret_cast<char *>(buf), buf_size);
        if (r > 0) return r;
        int err = srt_getlasterror(nullptr);
        if (err == SRT_EASYNCRCV || err == SRT_ETIMEOUT) {
            SRT_SOCKSTATUS st = srt_getsockstate(ctx->sock);
            if (st == SRTS_BROKEN || st == SRTS_CLOSED || st == SRTS_NONEXIST)
                return AVERROR_EOF;
            continue;
        }
        return AVERROR_EOF;
    }
    return AVERROR_EOF;
}

int SrtAvioInterrupt(void *opaque)
{
    auto *inst = static_cast<SrtSourceInstance *>(opaque);
    return (!inst || !inst->running.load()) ? 1 : 0;
}

speaker_layout ChannelsToObsSpeakers(int ch)
{
    switch (ch) {
    case 1: return SPEAKERS_MONO;
    case 2: return SPEAKERS_STEREO;
    case 3: return SPEAKERS_2POINT1;
    case 4: return SPEAKERS_4POINT0;
    case 5: return SPEAKERS_4POINT1;
    case 6: return SPEAKERS_5POINT1;
    case 8: return SPEAKERS_7POINT1;
    default: return SPEAKERS_STEREO;
    }
}

void SrtSourceWorker(SrtSourceInstance *inst)
{
    while (inst->running.load()) {
        SrtEndpoint ep;
        bool reconnect = true;
        bool wantAudio = true;
        {
            std::lock_guard<std::mutex> lock(inst->cfg_mutex);
            ep = inst->ep;
            reconnect = inst->auto_reconnect;
            wantAudio = inst->enable_audio;
        }

        std::string peer, err;
        SRTSOCKET sock = OpenSrtSocket(ep, false, inst->running, &peer, &err);
        if (sock == SRT_INVALID_SOCK) {
            if (!inst->running.load() || !reconnect) break;
            __android_log_print(ANDROID_LOG_WARN, kTag, "SRT source connect failed (%s): %s",
                                ep.host.c_str(), err.c_str());
            for (int i = 0; i < 15 && inst->running.load(); ++i)
                std::this_thread::sleep_for(std::chrono::milliseconds(100));
            continue;
        }
        inst->active_sock.store(sock);
        __android_log_print(ANDROID_LOG_INFO, kTag, "SRT source connected peer=%s mode=%s lat=%dms",
                            peer.c_str(), ep.mode.c_str(), ep.latency_ms);

        SrtAvioReadCtx ioCtx{inst, sock};
        uint8_t *ioBuf = static_cast<uint8_t *>(av_malloc(32768));
        AVIOContext *avio = avio_alloc_context(ioBuf, 32768, 0, &ioCtx, &SrtAvioReadPacket, nullptr, nullptr);
        AVFormatContext *fmt = avformat_alloc_context();
        if (!avio || !fmt) {
            if (avio) { av_freep(&avio->buffer); avio_context_free(&avio); }
            else av_free(ioBuf);
            if (fmt) avformat_free_context(fmt);
            if (inst->active_sock.exchange(SRT_INVALID_SOCK) == sock) srt_close(sock);
            break;
        }
        fmt->pb = avio;
        fmt->flags |= AVFMT_FLAG_CUSTOM_IO | AVFMT_FLAG_NOBUFFER;
        fmt->interrupt_callback.callback = &SrtAvioInterrupt;
        fmt->interrupt_callback.opaque = inst;
        fmt->probesize = 512 * 1024;
        fmt->max_analyze_duration = 1500000;

        const AVInputFormat *tsFmt = av_find_input_format("mpegts");
        if (avformat_open_input(&fmt, nullptr, tsFmt, nullptr) == 0 &&
            avformat_find_stream_info(fmt, nullptr) >= 0) {
            int vIdx = av_find_best_stream(fmt, AVMEDIA_TYPE_VIDEO, -1, -1, nullptr, 0);
            int aIdx = wantAudio ? av_find_best_stream(fmt, AVMEDIA_TYPE_AUDIO, -1, vIdx, nullptr, 0) : -1;

            AVCodecContext *vDec = nullptr;
            AVCodecContext *aDec = nullptr;
            SwsContext *sws = nullptr;
            SwrContext *swr = nullptr;
            int swsW = 0, swsH = 0, swsFmt = AV_PIX_FMT_NONE;
            std::vector<uint8_t> i420Buf;

            if (vIdx >= 0) {
                const AVCodec *vc = avcodec_find_decoder(fmt->streams[vIdx]->codecpar->codec_id);
                if (vc) {
                    vDec = avcodec_alloc_context3(vc);
                    if (vDec && avcodec_parameters_to_context(vDec, fmt->streams[vIdx]->codecpar) >= 0) {
                        vDec->thread_count = 4;
                        vDec->flags |= AV_CODEC_FLAG_LOW_DELAY;
                        if (avcodec_open2(vDec, vc, nullptr) < 0) {
                            avcodec_free_context(&vDec);
                        }
                    }
                }
            }
            if (aIdx >= 0) {
                const AVCodec *ac = avcodec_find_decoder(fmt->streams[aIdx]->codecpar->codec_id);
                if (ac) {
                    aDec = avcodec_alloc_context3(ac);
                    if (aDec && avcodec_parameters_to_context(aDec, fmt->streams[aIdx]->codecpar) >= 0) {
                        if (avcodec_open2(aDec, ac, nullptr) < 0) {
                            avcodec_free_context(&aDec);
                        }
                    }
                }
            }

            AVPacket *pkt = av_packet_alloc();
            AVFrame *frm = av_frame_alloc();
            while (inst->running.load() && av_read_frame(fmt, pkt) >= 0) {
                if (pkt->stream_index == vIdx && vDec) {
                    if (avcodec_send_packet(vDec, pkt) == 0) {
                        while (avcodec_receive_frame(vDec, frm) == 0 && inst->running.load()) {
                            int w = frm->width, h = frm->height;
                            if (w > 0 && h > 0) {
                                if (!sws || swsW != w || swsH != h || swsFmt != frm->format) {
                                    if (sws) sws_freeContext(sws);
                                    sws = sws_getContext(w, h, static_cast<AVPixelFormat>(frm->format),
                                                         w, h, AV_PIX_FMT_YUV420P, SWS_BILINEAR, nullptr, nullptr, nullptr);
                                    swsW = w; swsH = h; swsFmt = frm->format;
                                    i420Buf.resize(size_t(w) * size_t(h) * 3 / 2);
                                }
                                if (sws) {
                                    uint8_t *dst[3] = {
                                        i420Buf.data(),
                                        i420Buf.data() + size_t(w) * size_t(h),
                                        i420Buf.data() + size_t(w) * size_t(h) * 5 / 4
                                    };
                                    int dstStride[3] = {w, w / 2, w / 2};
                                    sws_scale(sws, frm->data, frm->linesize, 0, h, dst, dstStride);

                                    obs_source_frame of{};
                                    of.data[0] = dst[0]; of.data[1] = dst[1]; of.data[2] = dst[2];
                                    of.linesize[0] = uint32_t(dstStride[0]);
                                    of.linesize[1] = uint32_t(dstStride[1]);
                                    of.linesize[2] = uint32_t(dstStride[2]);
                                    of.width = uint32_t(w);
                                    of.height = uint32_t(h);
                                    of.format = VIDEO_FORMAT_I420;
                                    of.timestamp = os_gettime_ns();
                                    bool full = (frm->color_range == AVCOL_RANGE_JPEG);
                                    of.full_range = full;
                                    video_format_get_parameters(h >= 720 ? VIDEO_CS_709 : VIDEO_CS_601,
                                                                full ? VIDEO_RANGE_FULL : VIDEO_RANGE_PARTIAL,
                                                                of.color_matrix, of.color_range_min, of.color_range_max);
                                    obs_source_t *ref = obs_source_get_ref(inst->source);
                                    if (ref) {
                                        obs_source_output_video(ref, &of);
                                        obs_source_release(ref);
                                    }
                                }
                            }
                            av_frame_unref(frm);
                        }
                    }
                } else if (pkt->stream_index == aIdx && aDec) {
                    if (avcodec_send_packet(aDec, pkt) == 0) {
                        while (avcodec_receive_frame(aDec, frm) == 0 && inst->running.load()) {
                            int sr = frm->sample_rate > 0 ? frm->sample_rate : 48000;
                            int ch = std::clamp(frm->ch_layout.nb_channels > 0 ? frm->ch_layout.nb_channels : 2, 1, 2);
                            if (!swr) {
                                AVChannelLayout outLayout{};
                                av_channel_layout_default(&outLayout, ch);
                                swr_alloc_set_opts2(&swr, &outLayout, AV_SAMPLE_FMT_FLTP, 48000,
                                                    &frm->ch_layout, static_cast<AVSampleFormat>(frm->format), sr, 0, nullptr);
                                if (swr && swr_init(swr) < 0) swr_free(&swr);
                                av_channel_layout_uninit(&outLayout);
                            }
                            if (swr) {
                                int outSamples = int(av_rescale_rnd(swr_get_delay(swr, sr) + frm->nb_samples, 48000, sr, AV_ROUND_UP));
                                std::vector<float> ch0(static_cast<size_t>(outSamples), 0.0f);
                                std::vector<float> ch1(static_cast<size_t>(outSamples), 0.0f);
                                uint8_t *outPlanes[2] = {
                                    reinterpret_cast<uint8_t *>(ch0.data()),
                                    reinterpret_cast<uint8_t *>(ch1.data())
                                };
                                int got = swr_convert(swr, outPlanes, outSamples,
                                                      const_cast<const uint8_t **>(frm->extended_data), frm->nb_samples);
                                if (got > 0) {
                                    obs_source_audio oa{};
                                    oa.data[0] = outPlanes[0];
                                    if (ch > 1) oa.data[1] = outPlanes[1];
                                    oa.frames = uint32_t(got);
                                    oa.samples_per_sec = 48000;
                                    oa.format = AUDIO_FORMAT_FLOAT_PLANAR;
                                    oa.speakers = ChannelsToObsSpeakers(ch);
                                    oa.timestamp = os_gettime_ns();
                                    obs_source_t *ref = obs_source_get_ref(inst->source);
                                    if (ref) {
                                        obs_source_output_audio(ref, &oa);
                                        obs_source_release(ref);
                                    }
                                }
                            }
                            av_frame_unref(frm);
                        }
                    }
                }
                av_packet_unref(pkt);
            }
            av_frame_free(&frm);
            av_packet_free(&pkt);
            if (sws) sws_freeContext(sws);
            if (swr) swr_free(&swr);
            if (vDec) avcodec_free_context(&vDec);
            if (aDec) avcodec_free_context(&aDec);
        }

        if (fmt) {
            fmt->pb = nullptr;
            avformat_close_input(&fmt);
        }
        if (avio) {
            av_freep(&avio->buffer);
            avio_context_free(&avio);
        }
        if (inst->active_sock.exchange(SRT_INVALID_SOCK) == sock) srt_close(sock);

        if (!inst->running.load() || !reconnect) break;
        for (int i = 0; i < 12 && inst->running.load(); ++i)
            std::this_thread::sleep_for(std::chrono::milliseconds(100));
    }
}

void StopSrtSourceWorker(SrtSourceInstance *inst)
{
    inst->running.store(false);
    SRTSOCKET s = inst->active_sock.exchange(SRT_INVALID_SOCK);
    if (s != SRT_INVALID_SOCK) srt_close(s);
    if (inst->worker.joinable()) inst->worker.join();
}

const char *srt_src_get_name(void *) { return "Fuente SRT (Wi-Fi / Datos moviles)"; }

void srt_src_get_defaults(obs_data_t *s)
{
    obs_data_set_default_string(s, "srt_mode", "caller");
    obs_data_set_default_string(s, "srt_url", "");
    obs_data_set_default_int(s, "srt_port", 9999);
    obs_data_set_default_int(s, "srt_latency_ms", 250);
    obs_data_set_default_string(s, "srt_streamid", "");
    obs_data_set_default_string(s, "srt_passphrase", "");
    obs_data_set_default_bool(s, "reconnect", true);
    obs_data_set_default_bool(s, "enable_audio", true);
}

obs_properties_t *srt_src_get_properties(void *)
{
    obs_properties_t *p = obs_properties_create();
    obs_properties_add_text(p, "info",
        "Recibe video y audio SRT por Wi-Fi o datos moviles (4G/5G).\n"
        "- Caller: conecta a un servidor/OBS/vMix/MediaMTX remoto.\n"
        "- Listener: recibe conexion entrante en el puerto local de este telefono.",
        OBS_TEXT_INFO);
    obs_property_t *mode = obs_properties_add_list(p, "srt_mode", "Modo SRT", OBS_COMBO_TYPE_LIST, OBS_COMBO_FORMAT_STRING);
    obs_property_list_add_string(mode, "Caller (Conectar a URL / IP remota)", "caller");
    obs_property_list_add_string(mode, "Listener (Esperar conexion en este telefono)", "listener");
    obs_properties_add_text(p, "srt_url", "URL o IP remota (ej: srt://IP:9999 o 192.168.1.10)", OBS_TEXT_DEFAULT);
    obs_properties_add_int(p, "srt_port", "Puerto SRT", 1024, 65535, 1);
    obs_properties_add_int(p, "srt_latency_ms", "Latencia SRT (ms, 200 Wi-Fi / 400 datos)", 20, 4000, 10);
    obs_properties_add_text(p, "srt_streamid", "Stream ID (opcional)", OBS_TEXT_DEFAULT);
    obs_properties_add_text(p, "srt_passphrase", "Contrasena AES (opcional, 10+ chars)", OBS_TEXT_PASSWORD);
    obs_properties_add_bool(p, "enable_audio", "Activar audio de la fuente SRT");
    obs_properties_add_bool(p, "reconnect", "Reconexion automatica (Wi-Fi / 4G)");
    return p;
}

void srt_src_update(void *data, obs_data_t *s)
{
    auto *inst = static_cast<SrtSourceInstance *>(data);
    StopSrtSourceWorker(inst);
    {
        std::lock_guard<std::mutex> lock(inst->cfg_mutex);
        std::string mode = obs_data_get_string(s, "srt_mode");
        std::string url = obs_data_get_string(s, "srt_url");
        int port = int(obs_data_get_int(s, "srt_port"));
        int lat = int(obs_data_get_int(s, "srt_latency_ms"));
        std::string sid = obs_data_get_string(s, "srt_streamid");
        std::string pass = obs_data_get_string(s, "srt_passphrase");
        inst->ep = ParseSrtConfig(url, mode, port, lat, sid, pass);
        inst->auto_reconnect = obs_data_get_bool(s, "reconnect");
        inst->enable_audio = obs_data_get_bool(s, "enable_audio");
    }
    if (inst->ep.mode == "listener" || !inst->ep.host.empty()) {
        inst->running.store(true);
        inst->worker = std::thread(SrtSourceWorker, inst);
    }
}

void *srt_src_create(obs_data_t *s, obs_source_t *src)
{
    auto *inst = new SrtSourceInstance();
    inst->source = src;
    obs_source_set_async_unbuffered(src, false);
    srt_src_update(inst, s);
    return inst;
}

void srt_src_destroy(void *data)
{
    auto *inst = static_cast<SrtSourceInstance *>(data);
    if (!inst) return;
    StopSrtSourceWorker(inst);
    delete inst;
}

// ==================== 2. SALIDA SRT (OBS ENCODED OUTPUT FOR TOOLS) ====================

struct EncodedPkt {
    std::vector<uint8_t> data;
    int64_t pts{};
    int64_t dts{};
    int timebase_den{};
    bool is_video{true};
    bool keyframe{false};
};

struct SrtOutputInstance {
    obs_output_t *output{};
    SrtEndpoint ep;
    std::atomic<bool> running{false};
    std::atomic<SRTSOCKET> sock{SRT_INVALID_SOCK};
    std::thread sender_thread;

    std::mutex q_mutex;
    std::condition_variable q_cv;
    std::deque<EncodedPkt> queue;
    bool waiting_for_keyframe{true};

    std::vector<uint8_t> v_extradata;
    std::vector<uint8_t> a_extradata;
    int width{1280};
    int height{720};
    int sample_rate{48000};
    int channels{2};
    int v_bitrate{3500000};

    // Telemetry
    std::mutex st_mutex;
    std::string state{"Detenido"};
    std::string detail;
    double kbps{0.0};
    double rtt_ms{0.0};
    int64_t pkt_lost{0};
    int64_t pkt_retrans{0};
    uint64_t start_ms{0};
};

struct SrtAvioWriteCtx {
    SrtOutputInstance *out;
    SRTSOCKET sock;
    std::vector<uint8_t> pending;
};

int SrtAvioWritePacket(void *opaque, const uint8_t *buf, int buf_size)
{
    auto *ctx = static_cast<SrtAvioWriteCtx *>(opaque);
    if (!ctx || !ctx->out || !ctx->out->running.load() || ctx->sock == SRT_INVALID_SOCK)
        return AVERROR_EOF;
    if (buf_size <= 0) return 0;

    ctx->pending.insert(ctx->pending.end(), buf, buf + buf_size);
    size_t offset = 0;
    while (ctx->pending.size() - offset >= size_t(kSrtChunkSize)) {
        int sent = srt_sendmsg2(ctx->sock, reinterpret_cast<const char *>(ctx->pending.data() + offset),
                                kSrtChunkSize, nullptr);
        if (sent == SRT_ERROR) {
            return AVERROR(EIO);
        }
        offset += size_t(kSrtChunkSize);
    }
    if (offset > 0) {
        ctx->pending.erase(ctx->pending.begin(), ctx->pending.begin() + ptrdiff_t(offset));
    }
    return buf_size;
}

void UpdateOutStatus(SrtOutputInstance *o, const std::string &st, const std::string &det)
{
    std::lock_guard<std::mutex> lock(o->st_mutex);
    o->state = st;
    o->detail = det;
}

void SrtOutputSenderLoop(SrtOutputInstance *o)
{
    while (o->running.load()) {
        UpdateOutStatus(o, o->ep.mode == "listener" ? "Esperando conexion..." : "Conectando...",
                        o->ep.mode == "listener"
                            ? ("Puerto " + std::to_string(o->ep.port))
                            : (o->ep.host + ":" + std::to_string(o->ep.port)));

        std::string peer, err;
        SRTSOCKET sock = OpenSrtSocket(o->ep, true, o->running, &peer, &err);
        if (sock == SRT_INVALID_SOCK) {
            if (!o->running.load()) break;
            UpdateOutStatus(o, "Reconectando...", err);
            for (int i = 0; i < 15 && o->running.load(); ++i)
                std::this_thread::sleep_for(std::chrono::milliseconds(100));
            continue;
        }
        o->sock.store(sock);
        UpdateOutStatus(o, "Transmitiendo", peer);

        std::vector<uint8_t> videoConfig, audioConfig;
        {
            std::unique_lock<std::mutex> lock(o->q_mutex);
            o->q_cv.wait(lock, [&] {
                return !o->running.load() ||
                    (!o->queue.empty() && !o->v_extradata.empty() && !o->a_extradata.empty());
            });
            if (!o->running.load()) {
                if (o->sock.exchange(SRT_INVALID_SOCK) == sock) srt_close(sock);
                break;
            }
            videoConfig = o->v_extradata;
            audioConfig = o->a_extradata;
        }

        // Setup MPEG-TS muxer over SRT AVIO
        SrtAvioWriteCtx ioCtx{o, sock, {}};
        uint8_t *ioBuf = static_cast<uint8_t *>(av_malloc(kSrtChunkSize * 4));
        AVIOContext *avio = ioBuf ? avio_alloc_context(ioBuf, kSrtChunkSize * 4, 1, &ioCtx, nullptr, &SrtAvioWritePacket, nullptr) : nullptr;
        if (!avio) av_free(ioBuf);
        AVFormatContext *fmt = nullptr;
        avformat_alloc_output_context2(&fmt, nullptr, "mpegts", nullptr);
        bool muxOk = false;
        AVStream *vs = nullptr;
        AVStream *as = nullptr;

        if (avio && fmt) {
            fmt->pb = avio;
            fmt->flags |= AVFMT_FLAG_CUSTOM_IO | AVFMT_FLAG_FLUSH_PACKETS;

            vs = avformat_new_stream(fmt, nullptr);
            if (vs) {
                vs->codecpar->codec_type = AVMEDIA_TYPE_VIDEO;
                vs->codecpar->codec_id = AV_CODEC_ID_H264;
                vs->codecpar->width = o->width;
                vs->codecpar->height = o->height;
                vs->codecpar->bit_rate = o->v_bitrate;
                vs->time_base = AVRational{1, 90000};
                if (!videoConfig.empty()) {
                    vs->codecpar->extradata = static_cast<uint8_t *>(av_mallocz(videoConfig.size() + AV_INPUT_BUFFER_PADDING_SIZE));
                    if (vs->codecpar->extradata) std::memcpy(vs->codecpar->extradata, videoConfig.data(), videoConfig.size());
                    vs->codecpar->extradata_size = int(videoConfig.size());
                }
            }
            as = avformat_new_stream(fmt, nullptr);
            if (as) {
                as->codecpar->codec_type = AVMEDIA_TYPE_AUDIO;
                as->codecpar->codec_id = AV_CODEC_ID_AAC;
                as->codecpar->sample_rate = o->sample_rate;
                av_channel_layout_default(&as->codecpar->ch_layout, o->channels);
                as->codecpar->bit_rate = 128000;
                as->time_base = AVRational{1, 90000};
                if (!audioConfig.empty()) {
                    as->codecpar->extradata = static_cast<uint8_t *>(av_mallocz(audioConfig.size() + AV_INPUT_BUFFER_PADDING_SIZE));
                    if (as->codecpar->extradata) std::memcpy(as->codecpar->extradata, audioConfig.data(), audioConfig.size());
                    as->codecpar->extradata_size = int(audioConfig.size());
                }
            }
            AVDictionary *opts = nullptr;
            av_dict_set(&opts, "mpegts_flags", "resend_headers", 0);
            muxOk = vs && as && vs->codecpar->extradata && as->codecpar->extradata &&
                avformat_write_header(fmt, &opts) >= 0;
            av_dict_free(&opts);
        }

        bool waitedKey = false;
        auto lastStatAt = std::chrono::steady_clock::now();
        AVPacket *avp = av_packet_alloc();

        while (o->running.load() && muxOk && avp) {
            EncodedPkt epkt;
            {
                std::unique_lock<std::mutex> lock(o->q_mutex);
                o->q_cv.wait_for(lock, std::chrono::milliseconds(150), [&]() {
                    return !o->running.load() || !o->queue.empty();
                });
                if (!o->running.load()) break;
                if (o->queue.empty()) continue;
                epkt = std::move(o->queue.front());
                o->queue.pop_front();
            }

            if (epkt.is_video && !waitedKey) {
                if (!epkt.keyframe) continue;
                waitedKey = true;
            }
            if (!waitedKey) continue;

            av_packet_unref(avp);
            if (av_new_packet(avp, int(epkt.data.size())) == 0) {
                std::memcpy(avp->data, epkt.data.data(), epkt.data.size());
                AVStream *st = epkt.is_video ? vs : as;
                avp->stream_index = st->index;
                // Convert OBS packet pts/dts from encoder timebase into 90kHz MPEG-TS timebase
                // OBS encodes the fractional frame duration into the tick increment.
                AVRational srcTb{1, epkt.timebase_den};
                avp->pts = av_rescale_q(epkt.pts, srcTb, st->time_base);
                avp->dts = av_rescale_q(epkt.dts, srcTb, st->time_base);
                if (avp->dts > avp->pts) avp->dts = avp->pts;
                if (epkt.keyframe) avp->flags |= AV_PKT_FLAG_KEY;
                if (av_interleaved_write_frame(fmt, avp) < 0) {
                    UpdateOutStatus(o, "Reconectando...", "Conexion SRT interrumpida");
                    break;
                }
            }

            auto now = std::chrono::steady_clock::now();
            if (std::chrono::duration_cast<std::chrono::milliseconds>(now - lastStatAt).count() >= 1000) {
                lastStatAt = now;
                SRT_TRACEBSTATS st{};
                if (srt_bistats(sock, &st, 0, 1) == 0) {
                    std::lock_guard<std::mutex> lock(o->st_mutex);
                    o->kbps = st.mbpsSendRate * 1000.0;
                    o->rtt_ms = st.msRTT;
                    o->pkt_lost = st.pktSndLossTotal;
                    o->pkt_retrans = st.pktRetransTotal;
                }
            }
        }

        av_packet_free(&avp);
        if (fmt) {
            if (muxOk) av_write_trailer(fmt);
            fmt->pb = nullptr;
            avformat_free_context(fmt);
        }
        if (avio) {
            av_freep(&avio->buffer);
            avio_context_free(&avio);
        }
        if (o->sock.exchange(SRT_INVALID_SOCK) == sock) srt_close(sock);

        if (!o->running.load()) break;
        for (int i = 0; i < 12 && o->running.load(); ++i)
            std::this_thread::sleep_for(std::chrono::milliseconds(100));
    }
    UpdateOutStatus(o, "Detenido", "");
}

// Singleton active SRT Tools output managed from Herramientas -> Salida SRT
std::mutex g_srt_tool_mutex;
obs_output_t *g_srt_tool_out = nullptr;
obs_encoder_t *g_srt_tool_venc = nullptr;
obs_encoder_t *g_srt_tool_aenc = nullptr;
SrtOutputInstance *g_srt_tool_inst = nullptr;

const char *srt_out_get_name(void *) { return "Android SRT Output"; }

void *srt_out_create(obs_data_t *s, obs_output_t *output)
{
    auto *o = new SrtOutputInstance();
    o->output = output;
    if (s) {
        std::string mode = obs_data_get_string(s, "mode");
        std::string host = obs_data_get_string(s, "host");
        int port = int(obs_data_get_int(s, "port"));
        int lat = int(obs_data_get_int(s, "latency_ms"));
        std::string sid = obs_data_get_string(s, "streamid");
        std::string pass = obs_data_get_string(s, "passphrase");
        o->ep = ParseSrtConfig(host, mode, port, lat, sid, pass);
        o->v_bitrate = int(obs_data_get_int(s, "bitrate_kbps")) * 1000;
        if (o->v_bitrate < 300000) o->v_bitrate = 3500000;
    }
    return o;
}

void srt_out_stop_internal(SrtOutputInstance *o)
{
    o->running.store(false);
    SRTSOCKET s = o->sock.exchange(SRT_INVALID_SOCK);
    if (s != SRT_INVALID_SOCK) srt_close(s);
    o->q_cv.notify_all();
    if (o->sender_thread.joinable()) o->sender_thread.join();
    std::lock_guard<std::mutex> lock(o->q_mutex);
    o->queue.clear();
    o->waiting_for_keyframe = true;
    o->v_extradata.clear();
    o->a_extradata.clear();
}

void srt_out_destroy(void *data)
{
    auto *o = static_cast<SrtOutputInstance *>(data);
    if (!o) return;
    srt_out_stop_internal(o);
    {
        std::lock_guard<std::mutex> lock(g_srt_tool_mutex);
        if (g_srt_tool_inst == o)
            g_srt_tool_inst = nullptr;
    }
    delete o;
}

bool srt_out_start(void *data)
{
    auto *o = static_cast<SrtOutputInstance *>(data);
    if (!obs_output_can_begin_data_capture(o->output, 0))
        return false;
    if (!obs_output_initialize_encoders(o->output, 0))
        return false;

    obs_encoder_t *venc = obs_output_get_video_encoder(o->output);
    obs_encoder_t *aenc = obs_output_get_audio_encoder(o->output, 0);
    if (venc) {
        o->width = int(obs_encoder_get_width(venc));
        o->height = int(obs_encoder_get_height(venc));
    }
    if (aenc) {
        o->sample_rate = int(obs_encoder_get_sample_rate(aenc));
        if (o->sample_rate <= 0) o->sample_rate = 48000;
    }

    o->start_ms = os_gettime_ns() / 1000000ULL;
    o->running.store(true);
    o->sender_thread = std::thread(SrtOutputSenderLoop, o);
    obs_output_begin_data_capture(o->output, 0);
    return true;
}

void srt_out_stop(void *data, uint64_t)
{
    auto *o = static_cast<SrtOutputInstance *>(data);
    obs_output_end_data_capture(o->output);
    srt_out_stop_internal(o);
}

void srt_out_encoded_packet(void *data, struct encoder_packet *packet)
{
    auto *o = static_cast<SrtOutputInstance *>(data);
    if (!o || !o->running.load() || !packet || !packet->data || packet->size == 0 || packet->timebase_den <= 0)
        return;

    EncodedPkt ep;
    ep.is_video = (packet->type == OBS_ENCODER_VIDEO);
    ep.keyframe = packet->keyframe;
    ep.pts = packet->pts;
    ep.dts = packet->dts;
    ep.timebase_den = packet->timebase_den;

    std::lock_guard<std::mutex> lock(o->q_mutex);
    auto &config = ep.is_video ? o->v_extradata : o->a_extradata;
    if (config.empty()) {
        uint8_t *extra = nullptr;
        size_t size = 0;
        if (obs_encoder_get_extra_data(packet->encoder, &extra, &size) && extra && size)
            config.assign(extra, extra + size);
    }
    if (o->queue.size() >= 180) {
        o->queue.clear();
        o->waiting_for_keyframe = true;
    }
    if (o->waiting_for_keyframe) {
        if (!ep.is_video || !ep.keyframe || o->v_extradata.empty()) return;
        o->waiting_for_keyframe = false;
    }

    // Prepend SPS/PPS header on keyframes so late-joining SRT clients decode immediately
    if (ep.is_video && ep.keyframe && !o->v_extradata.empty()) {
        // Check if packet doesn't already start with SPS (NAL type 7)
        bool hasSps = (packet->size > 4 && packet->data[0] == 0 && packet->data[1] == 0 &&
                       ((packet->data[2] == 1 && (packet->data[3] & 0x1F) == 7) ||
                        (packet->data[2] == 0 && packet->data[3] == 1 && (packet->data[4] & 0x1F) == 7)));
        if (!hasSps) {
            ep.data.reserve(o->v_extradata.size() + packet->size);
            ep.data.insert(ep.data.end(), o->v_extradata.begin(), o->v_extradata.end());
        }
    }
    ep.data.insert(ep.data.end(), packet->data, packet->data + packet->size);

    o->queue.push_back(std::move(ep));
    o->q_cv.notify_one();
}

std::string JsonEscape(const std::string &in)
{
    std::string o;
    for (char c : in) {
        if (c == '"' || c == '\\') o.push_back('\\');
        if (c == '\n') o += "\\n";
        else if (c != '\r') o.push_back(c);
    }
    return o;
}
} // namespace

void android_srt_register()
{
    if (g_srt_registered) return;

    obs_source_info srcInfo{};
    srcInfo.id = "android_srt_source";
    srcInfo.type = OBS_SOURCE_TYPE_INPUT;
    srcInfo.output_flags = OBS_SOURCE_ASYNC_VIDEO | OBS_SOURCE_AUDIO | OBS_SOURCE_DO_NOT_DUPLICATE;
    srcInfo.get_name = srt_src_get_name;
    srcInfo.create = srt_src_create;
    srcInfo.destroy = srt_src_destroy;
    srcInfo.get_defaults = srt_src_get_defaults;
    srcInfo.get_properties = srt_src_get_properties;
    srcInfo.update = srt_src_update;
    obs_register_source(&srcInfo);

    obs_output_info outInfo{};
    outInfo.id = "android_srt_output";
    outInfo.flags = OBS_OUTPUT_AV | OBS_OUTPUT_ENCODED;
    outInfo.get_name = srt_out_get_name;
    outInfo.create = srt_out_create;
    outInfo.destroy = srt_out_destroy;
    outInfo.start = srt_out_start;
    outInfo.stop = srt_out_stop;
    outInfo.encoded_packet = srt_out_encoded_packet;
    outInfo.encoded_video_codecs = "h264";
    outInfo.encoded_audio_codecs = "aac";
    obs_register_output(&outInfo);

    g_srt_registered = true;
}

void android_srt_shutdown()
{
    obs_output_t *out = nullptr;
    obs_encoder_t *venc = nullptr;
    obs_encoder_t *aenc = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_srt_tool_mutex);
        out = g_srt_tool_out;
        venc = g_srt_tool_venc;
        aenc = g_srt_tool_aenc;
        g_srt_tool_out = nullptr;
        g_srt_tool_venc = nullptr;
        g_srt_tool_aenc = nullptr;
        g_srt_tool_inst = nullptr;
    }
    if (out) {
        if (obs_output_reconnecting(out))
            obs_output_force_stop(out);
        else if (obs_output_active(out))
            obs_output_stop(out);
        for (int i = 0; i < 40 && obs_output_active(out); ++i)
            std::this_thread::sleep_for(std::chrono::milliseconds(15));
        if (obs_output_active(out))
            obs_output_force_stop(out);
        obs_output_release(out);
    }
    if (venc) obs_encoder_release(venc);
    if (aenc) obs_encoder_release(aenc);
}

extern "C" __attribute__((visibility("default"))) bool obs_android_srt_output_start(
    const char *mode, const char *host, int port, int latency_ms,
    const char *streamid, const char *passphrase, int bitrate_kbps)
{
    android_srt_shutdown();
    std::lock_guard<std::mutex> lock(g_srt_tool_mutex);
    if (!obs_initialized()) return false;

    video_t *video = obs_get_video();
    audio_t *audio = obs_get_audio();
    if (!video || !audio) return false;

    obs_data_t *vs = obs_data_create();
    obs_data_set_int(vs, "bitrate", bitrate_kbps >= 400 ? bitrate_kbps : 3500);
    obs_data_set_int(vs, "keyint_sec", 2);
    g_srt_tool_venc = obs_video_encoder_create("android_mediacodec_h264", "android_srt_venc", vs, nullptr);
    obs_data_release(vs);
    if (!g_srt_tool_venc) return false;
    obs_encoder_set_video(g_srt_tool_venc, video);

    obs_data_t *as = obs_data_create();
    obs_data_set_int(as, "bitrate", 128);
    g_srt_tool_aenc = obs_audio_encoder_create("android_mediacodec_aac", "android_srt_aenc", as, 0, nullptr);
    obs_data_release(as);
    if (!g_srt_tool_aenc) {
        obs_encoder_release(g_srt_tool_venc); g_srt_tool_venc = nullptr;
        return false;
    }
    obs_encoder_set_audio(g_srt_tool_aenc, audio);

    obs_data_t *os = obs_data_create();
    obs_data_set_string(os, "mode", (mode && *mode) ? mode : "caller");
    obs_data_set_string(os, "host", host ? host : "");
    obs_data_set_int(os, "port", port > 0 ? port : 9999);
    obs_data_set_int(os, "latency_ms", latency_ms > 0 ? latency_ms : 250);
    obs_data_set_string(os, "streamid", streamid ? streamid : "");
    obs_data_set_string(os, "passphrase", passphrase ? passphrase : "");
    obs_data_set_int(os, "bitrate_kbps", bitrate_kbps >= 400 ? bitrate_kbps : 3500);

    g_srt_tool_out = obs_output_create("android_srt_output", "android_srt_tool_output", os, nullptr);
    obs_data_release(os);
    if (!g_srt_tool_out) {
        obs_encoder_release(g_srt_tool_venc); g_srt_tool_venc = nullptr;
        obs_encoder_release(g_srt_tool_aenc); g_srt_tool_aenc = nullptr;
        return false;
    }

    obs_output_set_video_encoder(g_srt_tool_out, g_srt_tool_venc);
    obs_output_set_audio_encoder(g_srt_tool_out, g_srt_tool_aenc, 0);
    if (!obs_output_start(g_srt_tool_out)) {
        obs_output_release(g_srt_tool_out); g_srt_tool_out = nullptr;
        obs_encoder_release(g_srt_tool_venc); g_srt_tool_venc = nullptr;
        obs_encoder_release(g_srt_tool_aenc); g_srt_tool_aenc = nullptr;
        return false;
    }
    g_srt_tool_inst = static_cast<SrtOutputInstance *>(obs_output_get_type_data(g_srt_tool_out));
    return true;
}

extern "C" __attribute__((visibility("default"))) void obs_android_srt_output_stop()
{
    android_srt_shutdown();
}

extern "C" __attribute__((visibility("default"))) bool obs_android_srt_output_status(char *out_buf, size_t out_cap)
{
    if (!out_buf || out_cap < 32) return false;
    std::lock_guard<std::mutex> lock(g_srt_tool_mutex);
    bool active = (g_srt_tool_out && obs_output_active(g_srt_tool_out));
    std::string state = "Detenido";
    std::string detail;
    double kbps = 0.0, rtt = 0.0;
    int64_t lost = 0, retrans = 0;
    uint64_t upSec = 0;
    if (active && g_srt_tool_inst) {
        std::lock_guard<std::mutex> sl(g_srt_tool_inst->st_mutex);
        state = g_srt_tool_inst->state;
        detail = g_srt_tool_inst->detail;
        kbps = g_srt_tool_inst->kbps;
        rtt = g_srt_tool_inst->rtt_ms;
        lost = g_srt_tool_inst->pkt_lost;
        retrans = g_srt_tool_inst->pkt_retrans;
        uint64_t nowMs = os_gettime_ns() / 1000000ULL;
        upSec = (nowMs > g_srt_tool_inst->start_ms) ? ((nowMs - g_srt_tool_inst->start_ms) / 1000ULL) : 0ULL;
    }
    std::snprintf(out_buf, out_cap,
                  "{\"active\":%s,\"state\":\"%s\",\"detail\":\"%s\",\"kbps\":%.0f,\"rtt\":%.1f,\"lost\":%lld,\"retrans\":%lld,\"uptime\":%llu}",
                  active ? "true" : "false", JsonEscape(state).c_str(), JsonEscape(detail).c_str(),
                  kbps, rtt, (long long)lost, (long long)retrans, (unsigned long long)upSec);
    return true;
}
