package io.quack.common.dto;

/** 群通话里的一个参与者 */
public record CallParticipant(String userId, String name, String avatar) {
}