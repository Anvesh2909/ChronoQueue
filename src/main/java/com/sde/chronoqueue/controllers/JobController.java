package com.sde.chronoqueue.controllers;


import com.sde.chronoqueue.dtos.JobCreateRequest;
import com.sde.chronoqueue.dtos.JobCreateResponse;
import com.sde.chronoqueue.entities.JobEntity;
import com.sde.chronoqueue.services.JobService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/jobs")
@RequiredArgsConstructor
public class JobController {
    private final JobService jobService;


    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public JobCreateResponse createJob(@RequestBody JobCreateRequest request){
        return jobService.createJob(request);
    }

}
