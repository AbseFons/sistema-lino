package com.tienda.inventario.domain;
public record SaleEntry(long id,String date,String customer,long totalCents,String documentType,
                        String documentStatus,int lines,String payments) {}
