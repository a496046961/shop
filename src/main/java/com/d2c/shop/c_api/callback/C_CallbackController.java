package com.d2c.shop.c_api.callback;

import com.alipay.api.AlipayConstants;
import com.alipay.api.internal.util.AlipaySignature;
import com.baomidou.mybatisplus.extension.api.R;
import com.d2c.shop.c_api.base.C_BaseController;
import com.d2c.shop.common.api.Response;
import com.d2c.shop.common.api.ResultCode;
import com.d2c.shop.common.sdk.pay.alipay.AliPayConfig;
import com.d2c.shop.common.sdk.pay.wxpay.WXPay;
import com.d2c.shop.common.sdk.pay.wxpay.config.WxPayConfig;
import com.d2c.shop.common.utils.ExecutorUtil;
import com.d2c.shop.common.utils.QueryUtil;
import com.d2c.shop.modules.order.model.OrderDO;
import com.d2c.shop.modules.order.model.PaymentDO;
import com.d2c.shop.modules.order.query.OrderQuery;
import com.d2c.shop.modules.order.service.OrderService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * @author Cai
 */
@Api(description = "第三方回调业务")
@RestController
@RequestMapping("/c_api/callback")
public class C_CallbackController extends C_BaseController {

    @Autowired
    private OrderService orderService;
    @Autowired
    private WxPayConfig wxPayConfig;

    @ApiOperation(value = "微信支付异步回调")
    @RequestMapping(value = "/wx_pay", method = RequestMethod.POST)
    public R wxPayNotify(HttpServletResponse response) throws Exception {
        WXPay wxPay = new WXPay(wxPayConfig);
        Map<String, String> requestData = wxPay.processResponseXml(this.readRequestBody(request));
        if (wxPay.isResponseSignatureValid(requestData)) {
            ExecutorUtil.fixedPool.submit(() -> {
                        if (requestData.get("result_code").equals("SUCCESS")) {
                            OrderQuery query = new OrderQuery();
                            query.setSn(requestData.get("out_trade_no"));
                            OrderDO order = orderService.getOne(QueryUtil.buildWrapper(query));
                            String total_fee = requestData.get("total_fee");
                            String order_fee = String.valueOf(order.getPayAmount().multiply(new BigDecimal(100)).intValue());
                            if (order != null && order.getStatus().equals(OrderDO.StatusEnum.WAIT_PAY.name()) && total_fee.equals(order_fee)) {
                                orderService.doPayment(order, PaymentDO.PaymentTypeEnum.WX_PAY, requestData.get("transaction_id"), requestData.get("mch_id"));
                            }
                        }
                    }
            );
            response.getWriter().write(successXML("SUCCESS", "OK"));
            response.getWriter().flush();
        }
        return Response.restResult(null, ResultCode.RESPONSE_DATA_NULL);
    }

    private String readRequestBody(HttpServletRequest request) throws IOException {
        InputStream inStream = request.getInputStream();
        ByteArrayOutputStream outSteam = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int len = 0;
        while ((len = inStream.read(buffer)) != -1) {
            outSteam.write(buffer, 0, len);
        }
        outSteam.close();
        inStream.close();
        String requestXml = new String(outSteam.toByteArray(), "utf-8");
        return requestXml;
    }

    private String successXML(String return_code, String return_msg) {
        return "<xml><return_code><![CDATA[" + return_code + "]]></return_code><return_msg><![CDATA[" + return_msg
                + "]]></return_msg></xml>";
    }

    @ApiOperation(value = "支付宝异步回调")
    @RequestMapping(value = "/ali_pay", method = RequestMethod.POST)
    public R aliPayNotify(HttpServletResponse response) throws Exception {
        Map<String, String> params = this.getRequestParamsMap();
        if (AlipaySignature.rsaCheckV1(params, AliPayConfig.PUBLIC_KEY, AlipayConstants.CHARSET_GBK, AlipayConstants.SIGN_TYPE_RSA2)) {
            ExecutorUtil.fixedPool.submit(() -> {
                        if (params.get("trade_status").equals("TRADE_SUCCESS") || params.get("trade_status").equals("TRADE_FINISHED")) {
                            OrderQuery oq = new OrderQuery();
                            oq.setSn(params.get("out_trade_no"));
                            OrderDO order = orderService.getOne(QueryUtil.buildWrapper(oq));
                            String total_amount = params.get("total_amount");
                            String order_fee = String.valueOf(order.getPayAmount());
                            if (order != null && order.getStatus().equals(OrderDO.StatusEnum.WAIT_PAY.name()) && total_amount.equals(order_fee)) {
                                orderService.doPayment(order, PaymentDO.PaymentTypeEnum.ALI_PAY, params.get("trade_no"), params.get("seller_id"));
                            }
                        }
                    }
            );
            response.getWriter().write("success");
            response.getWriter().flush();
        }
        return Response.restResult(null, ResultCode.RESPONSE_DATA_NULL);
    }

    private Map<String, String> getRequestParamsMap() {
        Map requestParams = request.getParameterMap();
        Map<String, String> params = new HashMap<String, String>();
        for (Iterator iter = requestParams.keySet().iterator(); iter.hasNext(); ) {
            String name = (String) iter.next();
            String[] values = (String[]) requestParams.get(name);
            String valueStr = "";
            for (int i = 0; i < values.length; i++) {
                valueStr = (i == values.length - 1) ? valueStr + values[i]
                        : valueStr + values[i] + ",";
            }
            params.put(name, valueStr);
        }
        return params;
    }

}
