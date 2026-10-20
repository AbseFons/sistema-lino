package com.tienda.inventario.domain;
public record SaleResult(long id,long totalCents,int lines,String documentType,String documentStatus,boolean repeated) {}
