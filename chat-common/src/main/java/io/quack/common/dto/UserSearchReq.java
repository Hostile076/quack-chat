package io.quack.common.dto;

/**
 * 搜索用户。
 *
 * @param keyword QC 号或用户名（两样都试，QC 号优先）
 */
public record UserSearchReq(String keyword) {
}
