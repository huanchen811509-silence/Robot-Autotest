package com.qa.autotest.api.caseasset;

import com.qa.autotest.application.caseasset.BatchUndoService;
import com.qa.autotest.common.ApiResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 批量撤销（FR-3.1-04）。 */
@RestController
@RequestMapping("/api/projects/{projectId}/batch")
public class BatchUndoController {

    private final BatchUndoService undo;

    public BatchUndoController(BatchUndoService undo) {
        this.undo = undo;
    }

    @PostMapping("/undo")
    public ApiResponse<BatchUndoService.UndoResult> undo(@PathVariable long projectId,
                                                          @RequestBody UndoRequest request) {
        return ApiResponse.ok(undo.undo(projectId, request == null ? null : request.token()));
    }

    public record UndoRequest(String token) {
    }
}
