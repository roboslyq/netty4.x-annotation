/**
 * Copyright (C), 2015-2020
 * FileName: RecyclerTest1
 * Author:   roboslyq
 * Date:     2020/7/21 9:03
 * Description: roboslyq
 * History:
 * <author>                 <time>          <version>          <desc>
 * luo.yongqian         2020/7/21 9:03      1.0.0               创建
 */
package io.netty.util;

/**
 *
 * 〈roboslyq〉
 * @author roboslyq
 * @date 2020/7/21
 * @since 1.0.0
 */
public class RecyclerTest1 {
    public static void main(String[] args) {
        User user = RECYCLER.get();
        user.setId("1");
        user.setName("roboslyq");
        System.out.println(user.id);
        user.recycle(user);
        User user1 = RECYCLER.get();
        System.out.println(user1.id);

        System.out.println(user == user1);
    }

    private static Recycler<User> RECYCLER = new Recycler<User>() {
        @Override
        protected User newObject(Handle<User> handle) {
            return new User(handle);
        }
    };

    private static class User{
        private String id;
        private String name;
        Recycler.Handle<User> handle;

        public User(Recycler.Handle<User> handle) {
            this.handle = handle;
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public void recycle(User user){
            this.handle.recycle(user);
        }
    }
}
