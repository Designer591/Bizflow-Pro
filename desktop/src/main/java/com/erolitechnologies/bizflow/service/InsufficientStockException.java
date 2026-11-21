package com.erolitechnologies.bizflow.service;

import com.erolitechnologies.bizflow.infra.AppException;

public class InsufficientStockException extends AppException {
    public InsufficientStockException(String product) {
        super("Not enough stock for \"" + product + "\". Reduce the quantity or receive more stock first.");
    }
}
