package com.tienda.inventario.domain;
public record PriceList(long id,String code,String name){
    @Override public String toString(){return name;}
}
