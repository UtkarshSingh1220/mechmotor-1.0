package com.mechmotor.service;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/** Enforces license features on proxied service calls so direct UI checks are not the only gate. */
@Aspect
@Component
public class LicenseFeatureAspect {
    private final LicenseService licenses;

    public LicenseFeatureAspect(LicenseService licenses) {
        this.licenses = licenses;
    }

    @Around("@within(requiresFeature)")
    public Object enforceClassFeature(ProceedingJoinPoint joinPoint, RequiresFeature requiresFeature) throws Throwable {
        licenses.requireFeature(requiresFeature.value());
        return joinPoint.proceed();
    }

    @Around("@annotation(requiresFeature)")
    public Object enforceMethodFeature(ProceedingJoinPoint joinPoint, RequiresFeature requiresFeature) throws Throwable {
        licenses.requireFeature(requiresFeature.value());
        return joinPoint.proceed();
    }
}
