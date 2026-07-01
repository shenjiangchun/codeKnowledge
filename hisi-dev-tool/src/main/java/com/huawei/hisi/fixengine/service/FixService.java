package com.huawei.hisi.fixengine.service;

import com.huawei.hisi.fixengine.agent.FixAgent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Thin service layer over {@link FixAgent}.
 */
@Slf4j
@Service
public class FixService {

    private final FixAgent fixAgent;

    public FixService(FixAgent fixAgent) {
        this.fixAgent = fixAgent;
    }

    /**
     * Generate a fix for the target method.
     *
     * @param methodSignature  FQN of the method under fix
     * @param exceptionType    simple class name of the exception
     * @param exceptionMessage exception message
     * @param entryParams      serialized entry-point parameters
     * @param sourceCode       current source of the method
     * @return corrected source code
     */
    public String fix(String methodSignature,
                      String exceptionType,
                      String exceptionMessage,
                      String entryParams,
                      String sourceCode) {
        log.info("[FixService] requesting fix for {}", methodSignature);
        return fixAgent.generateFix(methodSignature, exceptionType, exceptionMessage,
                entryParams, sourceCode);
    }
}
