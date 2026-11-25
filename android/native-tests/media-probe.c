// SPDX-License-Identifier: GPL-2.0-or-later
#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
#include <libavutil/pixdesc.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>

struct metrics { int frames, keys, first_y, last_y; int64_t samples; double start, end, key_time, max_key_gap, sumsq; int64_t values; };
static int drain(AVCodecContext *decoder, AVStream *stream, struct metrics *m, AVFrame *frame) {
    int result;
    while ((result = avcodec_receive_frame(decoder, frame)) == 0) {
        double time = frame->best_effort_timestamp == AV_NOPTS_VALUE ? m->end : frame->best_effort_timestamp * av_q2d(stream->time_base);
        if (!m->frames) m->start = time;
        m->end = time; m->frames++;
        if (decoder->codec_type == AVMEDIA_TYPE_VIDEO) {
            int pixel = frame->data[0][50 * frame->linesize[0] + 50];
            if (m->frames == 1) m->first_y = pixel;
            m->last_y = pixel;
            if (frame->flags & AV_FRAME_FLAG_KEY) {
                if (m->keys) { double gap = time - m->key_time; if (gap > m->max_key_gap) m->max_key_gap = gap; }
                m->key_time = time; m->keys++;
            }
        } else {
            m->samples += frame->nb_samples;
            if (frame->format == AV_SAMPLE_FMT_FLTP) {
                float *samples = (float *)frame->data[0];
                for (int i = 0; i < frame->nb_samples; ++i) { m->sumsq += samples[i]*samples[i]; m->values++; }
            }
        }
        av_frame_unref(frame);
    }
    return result == AVERROR(EAGAIN) || result == AVERROR_EOF ? 0 : result;
}
int main(int argc, char **argv) {
    if (argc != 2) return 2;
    av_log_set_level(AV_LOG_ERROR);
    AVFormatContext *input = NULL;
    if (avformat_open_input(&input, argv[1], NULL, NULL) < 0 || avformat_find_stream_info(input, NULL) < 0) { fprintf(stderr, "Cannot open received media\n"); return 3; }
    int video = av_find_best_stream(input, AVMEDIA_TYPE_VIDEO, -1, -1, NULL, 0);
    int audio = av_find_best_stream(input, AVMEDIA_TYPE_AUDIO, -1, -1, NULL, 0);
    if (video < 0 || audio < 0) return 4;
    AVCodecContext *decoders[2] = {0}; int indices[] = {video, audio}; struct metrics stats[2] = {{0}};
    for (int i = 0; i < 2; ++i) {
        AVCodecParameters *parameters = input->streams[indices[i]]->codecpar;
        const AVCodec *codec = avcodec_find_decoder(parameters->codec_id);
        decoders[i] = avcodec_alloc_context3(codec);
        if (!decoders[i] || avcodec_parameters_to_context(decoders[i], parameters) < 0 || avcodec_open2(decoders[i], codec, NULL) < 0) return 5;
    }
    AVPacket *packet = av_packet_alloc(); AVFrame *frame = av_frame_alloc(); int errors = 0;
    while (av_read_frame(input, packet) >= 0) {
        for (int i = 0; i < 2; ++i) if (packet->stream_index == indices[i]) {
            if (avcodec_send_packet(decoders[i], packet) < 0 || drain(decoders[i], input->streams[indices[i]], &stats[i], frame) < 0) errors++;
        }
        av_packet_unref(packet);
    }
    for (int i = 0; i < 2; ++i) { avcodec_send_packet(decoders[i], NULL); if (drain(decoders[i], input->streams[indices[i]], &stats[i], frame) < 0) errors++; }
    int width = decoders[0]->width, height = decoders[0]->height, rate = decoders[1]->sample_rate, channels = decoders[1]->ch_layout.nb_channels;
    double vd = stats[0].end - stats[0].start, ad = stats[1].samples / (double)rate;
    int pass = decoders[0]->codec_id == AV_CODEC_ID_H264 && decoders[1]->codec_id == AV_CODEC_ID_AAC &&
        width == 1280 && height == 720 && rate == 48000 && channels == 2 && stats[0].frames > 500 && stats[1].samples > 900000 &&
        stats[0].keys >= 8 && stats[0].max_key_gap < 2.2 && abs(stats[0].last_y - stats[0].first_y) > 15 && fabs(vd-ad) < .5 && !errors;
    printf("{\"pass\":%s,\"video_frames\":%d,\"width\":%d,\"height\":%d,\"video_duration\":%.3f,\"keyframes\":%d,\"max_keyframe_gap\":%.3f,\"first_y\":%d,\"last_y\":%d,\"audio_samples\":%lld,\"audio_rate\":%d,\"audio_channels\":%d,\"audio_duration\":%.3f,\"audio_rms\":%.7f,\"decode_errors\":%d}\n",
        pass ? "true" : "false", stats[0].frames,width,height,vd,stats[0].keys,stats[0].max_key_gap,stats[0].first_y,stats[0].last_y,(long long)stats[1].samples,rate,channels,ad,stats[1].values ? sqrt(stats[1].sumsq/stats[1].values) : 0,errors);
    av_frame_free(&frame); av_packet_free(&packet); for (int i=0;i<2;++i) avcodec_free_context(&decoders[i]); avformat_close_input(&input);
    return pass ? 0 : 1;
}
