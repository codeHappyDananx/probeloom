package dev.probeloom.web;

public class ApiResponse<T> {
    public boolean success;
    public T data;
    public String message;

    public static <T> ApiResponse<T> ok(T data) {
        ApiResponse<T> response = new ApiResponse<T>();
        response.success = true;
        response.data = data;
        return response;
    }

    public static <T> ApiResponse<T> fail(String message) {
        ApiResponse<T> response = new ApiResponse<T>();
        response.success = false;
        response.message = message;
        return response;
    }
}
