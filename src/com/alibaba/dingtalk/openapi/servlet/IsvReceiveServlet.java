package com.alibaba.dingtalk.openapi.servlet;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import com.alibaba.dingtalk.openapi.demo.Env;
import com.alibaba.dingtalk.openapi.demo.OApiException;
import com.alibaba.dingtalk.openapi.demo.OApiResultException;
import com.alibaba.dingtalk.openapi.demo.auth.AuthHelper;
import com.alibaba.dingtalk.openapi.demo.service.ServiceHelper;
import com.alibaba.dingtalk.openapi.demo.utils.FileUtils;
import com.alibaba.dingtalk.openapi.demo.utils.HttpHelper;
import com.alibaba.dingtalk.openapi.demo.utils.aes.DingTalkEncryptException;
import com.alibaba.dingtalk.openapi.demo.utils.aes.DingTalkEncryptor;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;

/**
 * Servlet implementation class IsvReceiveServlet 这个servlet用来接收钉钉服务器回调接口的推送
 */
public class IsvReceiveServlet extends HttpServlet {
	private static final long serialVersionUID = 1L;

	public IsvReceiveServlet() {
		super();
		// TODO Auto-generated constructor stub
	}

	protected void doGet(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {
		System.out.println("doGet12" + ":let's rock!" + request.getRequestURL());

		/** url中的签名 **/
		String msgSignature = request.getParameter("signature");
		// System.out.println("msgSignature" + msgS);

		/** url中的时间戳 **/
		String timeStamp = request.getParameter("timestamp");
		/** url中的随机字符串 **/
		String nonce = request.getParameter("nonce");

		/** post数据包数据中的加密数据 **/
		ServletInputStream sis = request.getInputStream();
		BufferedReader br = new BufferedReader(new InputStreamReader(sis));
		String line = null;
		StringBuilder sb = new StringBuilder();
		while ((line = br.readLine()) != null) {
			sb.append(line);
		}
		System.out.println("post12:" + sb);
		JSONObject jsonEncrypt = JSONObject.parseObject(sb.toString());
		String encrypt = "";

		try {
			encrypt = jsonEncrypt.getString("encrypt");
		} catch (Exception e) {
			e.printStackTrace();
		}

		/** 对encrypt进行解密 **/
		DingTalkEncryptor dingTalkEncryptor = null;
		String plainText = null;
		DingTalkEncryptException dingTalkEncryptException = null;
		try {
			// 对于DingTalkEncryptor的第三个参数，ISV进行配置的时候传对应套件的SUITE_KEY，普通企业传Corpid,由于此回调接口只有isv使用，所以传Env.SUITE_KEY
			dingTalkEncryptor = new DingTalkEncryptor(Env.TOKEN, Env.ENCODING_AES_KEY, Env.SUITE_KEY);
			plainText = dingTalkEncryptor.getDecryptMsg(msgSignature, timeStamp, nonce, encrypt);
		} catch (DingTalkEncryptException e) {
			// TODO Auto-generated catch block
			dingTalkEncryptException = e;
			// System.out.println(e.getMessage());
			// e.printStackTrace();
		} finally {
			if (dingTalkEncryptException != null) {
				if (dingTalkEncryptException.code == DingTalkEncryptException.COMPUTE_DECRYPT_TEXT_CORPID_ERROR) {
					try {
						dingTalkEncryptor = new DingTalkEncryptor(Env.TOKEN, Env.ENCODING_AES_KEY,
								Env.CREATE_SUITE_KEY);
						plainText = dingTalkEncryptor.getDecryptMsg(msgSignature, timeStamp, nonce, encrypt);
					} catch (DingTalkEncryptException e) {
						// TODO Auto-generated catch block
						System.out.println(e.getMessage());
						e.printStackTrace();
					}
				} else {
					System.out.println(dingTalkEncryptException.getMessage());
					dingTalkEncryptException.printStackTrace();
				}
			}
		}

		/** 对从encrypt解密出来的明文进行处理 **/
		JSONObject plainTextJson = JSONObject.parseObject(plainText);
		String eventType = plainTextJson.getString("EventType");
		String res = "success";
		switch (eventType) {
		case "suite_ticket":
			Env.suiteTicket = plainTextJson.getString("SuiteTicket");// do
																		// something
			String suiteToken = ServiceHelper.getSuiteToken(Env.SUITE_KEY, Env.SUITE_SECRET, Env.suiteTicket);
			System.out.println("suiteTicket12:" + Env.suiteTicket);
			System.out.println("suiteToken12:" + suiteToken);

			JSONObject json = new JSONObject();
			json.put("suiteTicket", Env.suiteTicket);
			json.put("suiteToken", suiteToken);
			FileUtils.write2File(json, "ticket");
			System.out.println("json:" + FileUtils.read2JSON("ticket").toJSONString());
			break;
		case "tmp_auth_code":
			System.out.println("tmp_auth_code12:" + ":let's rock!");
			Env.authCode = plainTextJson.getString("AuthCode");// do something
			Object value = FileUtils.getValue("ticket", "suiteToken");
			if (value == null) {
				break;
			}
			String suiteTokenPerm = value.toString();
			System.out.println("authCode12:" + Env.authCode + " suiteToken:" + suiteTokenPerm);
			JSONObject permanentJson = ServiceHelper.getPermanentCode(Env.authCode, suiteTokenPerm);
			String corpId = permanentJson.getJSONObject("auth_corp_info").getString("corpid");
			String permanent_code = permanentJson.getString("permanent_code");// 真实开发中，请务必将corpId和permanent_code做持久存储

			JSONObject jsonPerm = new JSONObject();
			jsonPerm.put(corpId, permanent_code);
			FileUtils.write2File(jsonPerm, "permanentcode");
			System.out.println("json perm:" + FileUtils.read2JSON("permanentcode").toJSONString() + " per code:"
					+ FileUtils.getValue("permanentcode", corpId));
			ServiceHelper.getActivateSuite(suiteTokenPerm, Env.SUITE_KEY, corpId, permanent_code);
			try {
				String accToken = null;
				String url = Env.OAPI_HOST + "/service/get_corp_token?" + "suite_access_token="
						+ FileUtils.getValue("ticket", "suiteToken");
				JSONObject args = new JSONObject();
				args.put("auth_corpid", corpId);
				args.put("permanent_code", FileUtils.getValue("permanentcode", corpId));
				JSONObject responseAccess = HttpHelper.httpPost(url, args);
				if (responseAccess.containsKey("access_token")) {
					accToken = responseAccess.getString("access_token");

					JSONObject jsonAccess = new JSONObject();
					JSONObject jsontemp = new JSONObject();
					jsontemp.clear();
					jsontemp.put("access_token", accToken);
					jsontemp.put("begin_time", System.currentTimeMillis());
					jsonAccess.put(corpId, jsontemp);

					FileUtils.write2File(jsonAccess, "accesstoken");
				} else {
					throw new OApiResultException("access_token");
				}

				String jsticket = AuthHelper
						.getJsapiTicket(ServiceHelper.getCorpToken(corpId, permanent_code, suiteTokenPerm), corpId);

				// save jsticket
				JSONObject jsonTicket = new JSONObject();
				JSONObject jsontemp = new JSONObject();
				jsontemp.clear();
				jsontemp.put("ticket", jsticket);
				jsontemp.put("begin_time", System.currentTimeMillis());
				jsonTicket.put(corpId, jsontemp);

				FileUtils.write2File(jsonTicket, "jsticket");
				System.out.println("jsticket12:" + jsticket);

			} catch (OApiException e1) {
				// TODO Auto-generated catch block
				System.out.println(e1.toString());
				e1.printStackTrace();
			}
			break;
		case "change_auth":
			String corpid = plainTextJson.getString("AuthCorpID");
			// 由于以下操作需要从持久化存储中获得数据，而本demo并没有做持久化存储（因为部署环境千差万别），所以没有具体代码，只有操作指导。
			// 1.根据corpid查询对应的permanent_code(永久授权码)
			// 2.调用『企业授权的授权数据』接口（ServiceHelper.getAuthInfo方法），此接口返回数据具体详情请查看文档。
			// 3.遍历从『企业授权的授权数据』接口中获取所有的微应用信息
			// 4.对每一个微应用都调用『获取企业的应用信息接口』（ServiceHelper.getAgent方法）
			/*
			 * 5.获取『获取企业的应用信息接口』接口返回值其中的"close"参数，才能得知微应用在企业用户做了授权变更之后的状态，有三种状态码
			 * ，分别为0，1，2.含义如下： 0:禁用（例如企业用户在OA后台禁用了微应用） 1:正常 (例如企业用户在禁用之后又启用了微应用)
			 * 2:待激活 (企业已经进行了授权，但是ISV还未为企业激活应用) 再根据具体状态做具体操作。 比如状态为0，可以不做任何操作，
			 * 比如状态为2，就需要ISV为企业进行激活授权套件的操作。
			 */

			break;
		case "check_create_suite_url":
			res = plainTextJson.getString("Random");
			String testSuiteKey = plainTextJson.getString("TestSuiteKey");
			break;

		case "check_update_suite_url":
			res = plainTextJson.getString("Random");
			break;
		default: // do something
			break;
		}

		/** 对返回信息进行加密 **/
		long timeStampLong = Long.parseLong(timeStamp);
		Map<String, String> jsonMap = null;
		try {
			jsonMap = dingTalkEncryptor.getEncryptedMap(res, timeStampLong, nonce);
		} catch (DingTalkEncryptException e) {
			System.out.println(e.getMessage());
			e.printStackTrace();
		}
		JSONObject json = new JSONObject();
		json.putAll(jsonMap);
		response.getWriter().append(json.toString());
	}

	protected void doPost(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {
		// TODO Auto-generated method stub
		doGet(request, response);
	}

}
