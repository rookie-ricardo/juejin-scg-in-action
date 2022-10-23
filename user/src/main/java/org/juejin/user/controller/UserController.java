package org.juejin.user.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class UserController {

    @GetMapping("/user/test")
    public String test() {
        return "user-api";
    }

    @GetMapping("/user/test1")
    public String test1() {
        return "user-api-test1";
    }

    @GetMapping("/user/test2")
    public String test2() {
        return "user-api-test2";
    }
}
