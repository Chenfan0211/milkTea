package com.wuling.product.dto;

import lombok.Data;

@Data
public class MallStockMutationResponse {
    private boolean success;
    private String message;

    public static MallStockMutationResponse ok() {
        return of(true, "ok");
    }

    public static MallStockMutationResponse fail(String message) {
        return of(false, message);
    }

    private static MallStockMutationResponse of(boolean success, String message) {
        MallStockMutationResponse response = new MallStockMutationResponse();
        response.setSuccess(success);
        response.setMessage(message);
        return response;
    }
}
