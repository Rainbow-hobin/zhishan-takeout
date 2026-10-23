package com.sky.service;

import com.sky.dto.ShoppingCartDTO;
import com.sky.entity.ShoppingCart;

import java.util.List;

public interface ShoppingCartService {

    /**
     * 添加购物车
     * @param shoppingCartDTO
     */
    void addShoppingCart(ShoppingCartDTO shoppingCartDTO);

    /**
     * 查看购物车
     * @return
     */
    List<ShoppingCart> showShoppingCart();

    /**
     * 清空购物车
     */
    void cleanShoppingCart();

    /**
     * 删除购物车中一个商品
     * @param shoppingCartDTO
     */
    void subShoppingCart(ShoppingCartDTO shoppingCartDTO);

    /**
     * 从购物车中整项移除某商品（不考虑数量）
     * @param shoppingCartDTO 定位商品（菜品/套餐 + 口味）
     */
    void removeItemFromCart(ShoppingCartDTO shoppingCartDTO);

    /**
     * 将购物车中某商品的数量设置为指定值；number ≤ 0 时整项移除
     * @param shoppingCartDTO 定位商品（菜品/套餐 + 口味）
     * @param number 目标数量
     */
    void updateItemQuantity(ShoppingCartDTO shoppingCartDTO, int number);

    /**
     * 再来一单：将历史订单明细批量加入当前用户的购物车，
     * 购物车中已存在的同款商品数量累加
     * @param shoppingCartList 由订单明细转换来的购物车对象
     */
    void addFromOrderDetails(List<ShoppingCart> shoppingCartList);
}
