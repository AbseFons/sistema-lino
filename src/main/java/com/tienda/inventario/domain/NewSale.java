package com.tienda.inventario.domain;
import java.util.List;
public record NewSale(String operationUuid,Long customerId,long priceListId,String documentType,
                      List<SaleLineInput> lines,List<SalePaymentInput> payments) {}
