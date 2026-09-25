package com.wuling.system.crud;

import com.wuling.common.api.PageResult;
import com.wuling.common.api.Result;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Set;

/**
 * 后台通用 CRUD 接口。
 *
 * 路由：/api/v1/admin/crud/{resource}
 * 资源白名单见 CrudRegistry，未登记的资源返回 404。
 * 鉴权：/api/v1/admin/** 需 JWT（由 SecurityConfig 统一控制）。
 *
 * 注意：固定路径（如 _resources）必须与 /{resource}/{id} 明确区分，
 * 这里把固定路径放在 /meta 前缀下，避免路径变量把 "_resources" 当成 id。
 */
@RestController
@RequestMapping("/api/v1/admin/crud")
public class CrudController {

    private final CrudService crudService;

    public CrudController(CrudService crudService) {
        this.crudService = crudService;
    }

    /** 支持的资源清单（便于前端自检） */
    @GetMapping("/meta/resources")
    public Result<Set<String>> resources() {
        return Result.ok(CrudRegistry.allResources());
    }

    @GetMapping("/{resource}")
    public Result<PageResult<Map<String, Object>>> page(@PathVariable String resource,
                                                        @RequestParam(defaultValue = "1") long current,
                                                        @RequestParam(defaultValue = "10") long size,
                                                        @RequestParam Map<String, String> params) {
        params.remove("current");
        params.remove("size");

        // 约定：以 eq_ 前缀的参数视为「等值过滤」，其余为「模糊搜索」。
        // 例：?eq_subjectType=STORE -> where subject_type = 'STORE'
        //     ?name=张三            -> where name like '%张三%'
        // 等值列仍受 CrudRegistry 的 filterable 白名单约束。
        Map<String, String> search = new java.util.LinkedHashMap<>();
        Map<String, String> filters = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (entry.getKey().startsWith("eq_")) {
                filters.put(entry.getKey().substring(3), entry.getValue());
            } else {
                search.put(entry.getKey(), entry.getValue());
            }
        }
        return Result.ok(crudService.page(resource, current, size, search, filters));
    }

    @GetMapping("/{resource}/{id}")
    public Result<Map<String, Object>> detail(@PathVariable String resource, @PathVariable long id) {
        return Result.ok(crudService.getOne(resource, id));
    }

    @PostMapping("/{resource}")
    public Result<Map<String, Object>> create(@PathVariable String resource,
                                              @RequestBody Map<String, Object> payload) {
        return Result.ok(crudService.create(resource, payload));
    }

    @PutMapping("/{resource}/{id}")
    public Result<Map<String, Object>> update(@PathVariable String resource,
                                              @PathVariable long id,
                                              @RequestBody Map<String, Object> payload) {
        return Result.ok(crudService.update(resource, id, payload));
    }

    @DeleteMapping("/{resource}/{id}")
    public Result<Void> delete(@PathVariable String resource, @PathVariable long id) {
        crudService.delete(resource, id);
        return Result.ok();
    }
}
