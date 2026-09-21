package com.wuling.auth.controller;

import com.wuling.common.api.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
public class RouteController {

    @GetMapping("/route/getConstantRoutes")
    public Result<List<Object>> getConstantRoutes() {
        return Result.ok(List.of());
    }

    @GetMapping("/route/getUserRoutes")
    public Result<Map<String, Object>> getUserRoutes() {
        return Result.ok(Map.of("routes", List.of(), "home", "home"));
    }

    @GetMapping("/route/isRouteExist")
    public Result<Boolean> isRouteExist() {
        return Result.ok(true);
    }
}
