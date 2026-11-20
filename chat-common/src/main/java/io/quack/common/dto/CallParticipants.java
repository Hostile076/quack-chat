package io.quack.common.dto;

import java.util.List;

/** 群通话参与者列表（谁已经接听） */
public record CallParticipants(String callId, String groupId, List<CallParticipant> members) {
}