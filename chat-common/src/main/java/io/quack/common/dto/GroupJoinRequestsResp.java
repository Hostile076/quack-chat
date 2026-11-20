package io.quack.common.dto;

import io.quack.common.model.GroupJoinRequest;

import java.util.List;

/** 待处理/我发出的入群申请 */
public record GroupJoinRequestsResp(List<GroupJoinRequest> requests) {
}
