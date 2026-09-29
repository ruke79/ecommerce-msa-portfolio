// package com.project.catalog_service.batch.config;

// import org.springframework.boot.CommandLineRunner;
// import org.springframework.batch.core.Job;
// import org.springframework.batch.core.launch.JobLauncher;
// import org.springframework.batch.core.JobParameters;
// import org.springframework.batch.core.JobParametersBuilder;
// import org.springframework.batch.core.configuration.annotation.EnableBatchProcessing;
// import org.springframework.context.annotation.Bean;
// import org.springframework.context.annotation.Configuration;

// @Configuration
// public class BatchJobRunner {

//     private final JobLauncher jobLauncher;
//     private final Job productImportJob;

//     public BatchJobRunner(JobLauncher jobLauncher, Job productImportJob) {
//         this.jobLauncher = jobLauncher;
//         this.productImportJob = productImportJob;
//     }

//     // @Bean 어노테이션을 제거하거나 메서드 자체를 주석 처리하여
//     // 애플리케이션 시작 시 자동으로 배치 작업을 실행하지 않도록 합니다.
//     @Bean
//     public CommandLineRunner runJob() {
//         return args -> {
//             try {
//                 // Job 실행 시 고유한 JobParameters를 생성
//                 JobParameters jobParameters = new JobParametersBuilder()
//                         .addLong("time", System.currentTimeMillis())
//                         .toJobParameters();

//                 jobLauncher.run(productImportJob, jobParameters);
//                 System.out.println("Batch job started successfully.");
//             } catch (Exception e) {
//                 System.err.println("Batch job failed: " + e.getMessage());
//                 e.printStackTrace();
//             }
//         };
//     }
// }
