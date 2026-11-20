package dev.probeloom.service;

/** 已进入远程调用但未取得确定结果，必须由操作人核查后放行。 */
final class RemoteOutcomeUnknownException extends RuntimeException {
    RemoteOutcomeUnknownException(String message, Throwable cause) { super(message, cause); }
}
