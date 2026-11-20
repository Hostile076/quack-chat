package io.quack.common.dto;

/** 通话音频帧：PCM 16kHz / 16bit / 单声道，base64 后传输 */
public record CallAudioReq(String callId, String data) {
}
