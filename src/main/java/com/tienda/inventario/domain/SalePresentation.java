package com.tienda.inventario.domain;
public record SalePresentation(long id,long productId,String sku,String product,String presentation,
                               long factorMilli,String unitCode,int unitDecimals,long stockMilli,
                               long listPriceMicro,Long latestCostBaseMicro) {
    @Override public String toString(){return sku+" · "+product+" · "+presentation+" (stock "+
            com.tienda.inventario.application.DecimalUnits.displayMilli(stockMilli)+" "+unitCode+")";}
}
