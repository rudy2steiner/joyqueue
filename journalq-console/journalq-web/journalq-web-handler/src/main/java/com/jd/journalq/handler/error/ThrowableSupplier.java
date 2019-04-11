package com.jd.journalq.handler.error;

import com.jd.journalq.exception.ServiceException;
import com.jd.laf.web.vertx.response.ErrorSupplier;
import com.jd.laf.web.vertx.response.Response;
import com.jd.laf.web.vertx.response.Responses;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

import java.lang.reflect.InvocationTargetException;

import static com.jd.journalq.handler.error.ErrorCode.*;

/**
 * Other error or exception conversion
 * Created by chenyanying3 on 18-11-16.
 */
public class ThrowableSupplier implements ErrorSupplier {
    private static final Logger logger = LoggerFactory.getLogger(ThrowableSupplier.class);

    @Override
    public Response error(final Throwable throwable) {
        logger.error("throwable exception",throwable);
        if (throwable instanceof IllegalArgumentException) {
            return Responses.error(BadRequest.getCode(), BadRequest.getStatus(), throwable.getMessage());
        } else if (throwable instanceof DuplicateKeyException) {
            return Responses.error(DuplicateError.getCode(), DuplicateError.getStatus(), "记录已经存在，请刷新重试");
        } else if (throwable instanceof DataIntegrityViolationException) {
            return Responses.error(RuntimeError.getCode(), RuntimeError.getStatus(), "输入记录不符合要求");
        } else if (throwable instanceof InvocationTargetException) {
            Throwable targetException = ((InvocationTargetException) throwable).getTargetException();
            if (targetException instanceof ServiceException) {
                ServiceException serviceException = (ServiceException) targetException;
                return Responses.error(ServiceError.getCode(), serviceException.getStatus(), serviceException.getMessage());
            }
            return Responses.error(RuntimeError.getCode(), RuntimeError.getStatus(), targetException.getMessage());
        }

//        return Responses.error(RuntimeError.getCode(), RuntimeError.getStatus(), StringUtils.isBlank(throwable.getMessage())? throwable.toString() : throwable.getMessage());
        return Responses.error(RuntimeError.getCode(), RuntimeError.getStatus(), StringUtils.isBlank(throwable.getMessage())?RuntimeError.getMessage(): throwable.getMessage());
    }

    @Override
    public Class<? extends Throwable> type() {
        return Throwable.class;
    }
}