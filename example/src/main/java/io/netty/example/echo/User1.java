/**
 * Copyright (C), 2015-2020
 * FileName: User
 * Author:   roboslyq
 * Date:     2020/7/30 11:12
 * Description:
 * History:
 * <author>                 <time>          <version>          <desc>
 * luo.yongqian         2020/7/30 11:12      1.0.0               创建
 */
package io.netty.example.echo;

/**
 *
 * 〈〉
 * @author roboslyq
 * @date 2020/7/30
 * @since 1.0.0
 */
public class User1 {
    private int id;
    private String name;

    public User1(int id, String name) {
        this.id = id;
        this.name = name;
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
