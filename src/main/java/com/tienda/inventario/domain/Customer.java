package com.tienda.inventario.domain;
public record Customer(long id,String type,String documentType,String documentNumber,String name,
                       String address,String phone,Long priceListId,boolean active) {
    @Override public String toString(){return name+(documentNumber==null?"":" · "+documentNumber);}
}
