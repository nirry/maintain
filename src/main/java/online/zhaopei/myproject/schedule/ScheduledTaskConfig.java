package online.zhaopei.myproject.schedule;

import com.github.pagehelper.PageHelper;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import online.zhaopei.myproject.common.tool.PaymentTool;
import online.zhaopei.myproject.config.ApplicationProp;
import online.zhaopei.myproject.constant.CommonConstant;
import online.zhaopei.myproject.domain.ecssent.InvtHead;
import online.zhaopei.myproject.domain.gjent.ImpPayHead;
import online.zhaopei.myproject.domain.gjpayment.*;
import online.zhaopei.myproject.service.ecssent.InvtHeadService;
import online.zhaopei.myproject.service.ecssent.PubRtnService;
import online.zhaopei.myproject.service.ecssent.ServerSystemService;
import online.zhaopei.myproject.service.ecssent.VeHeadService;
import online.zhaopei.myproject.service.gjent.ImpPayHeadService;
import online.zhaopei.myproject.service.gjent.PersonalInfoService;
import online.zhaopei.myproject.service.gjpayment.PaymentMessageService;
import online.zhaopei.myproject.service.para.SyncPaymentInfoService;
import org.apache.commons.io.FileUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.io.File;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.logging.Logger;

@Configuration
@EnableAsync
@EnableScheduling
public class ScheduledTaskConfig {

	private final static Log LOGGER = LogFactory.getLog(ScheduledTaskConfig.class);

	@Autowired
	private ImpPayHeadService impPayHeadService;

	@Autowired
	private PaymentMessageService paymentMessageService;

	@Autowired
	private SyncPaymentInfoService syncPaymentInfoService;

	@Autowired
	private InvtHeadService invtHeadService;
	
	@Autowired
	private PersonalInfoService personalInfoService;
	
	@Autowired
	private ServerSystemService serverSystemService;

	@Autowired
	private JavaMailSender mailSender;
	
	@Autowired
	private VeHeadService veHeadService;
	
	@Autowired
	private PubRtnService pubRtnService;
	
	@Autowired
	private ApplicationProp app;

	@Scheduled(cron = "0 0 1 * * *")
	public void deleteExportFile() throws Exception {
		SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd");
		String regex = "^[a-z_]{1,}" + sdf.format(Calendar.getInstance().getTime()) + "[0-9]{9}.[(csv)|(xls)]$";
		File file = new File("export");
		if (file.isDirectory()) {
			File[] children = file.listFiles();
			for (File f : children)
				if (!f.getName().matches(regex))
					f.delete();
		}
	}

	/**
	 * 每隔1小时间清空一下没有认证的身份信息，重新认证
	 */
//	@Scheduled(cron = "0 0 */1 * * *")
	public void clearErrorCount() throws Exception {
		this.personalInfoService.clearErrorCount();
	}

	/**
	 * 每隔1小时同步一次
	 * @throws Exception
	 */
	@Scheduled(cron = "0 0 0/1 * * *")
	public void reissueNonSyncInvtList() throws  Exception {
		SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMddHHmmssSSS");
		SimpleDateFormat sdf1 = new SimpleDateFormat("yyyyMMddHHmm");
		SimpleDateFormat sdf2 = new SimpleDateFormat("yyyyMMdd");
		Calendar calendar = Calendar.getInstance();
		calendar.add(Calendar.MINUTE, -60);
		String endDate = sdf1.format(calendar.getTime());
		calendar.add(Calendar.DATE, -1);
		String startDate = sdf2.format(calendar.getTime());
		PageHelper.startPage(1, 1000);
		List<InvtHead> invtList = this.invtHeadService.getNonSyncInvtList(startDate, endDate);
		String suffix = "_BuFaZbq.txt";
		String reissueFileName = null;
		File reissueTmpFile = null;
		File reissueNoticeTmpFile = null;
		File reissueFile = null;
		File reissueNoticeFile = null;
		PrintWriter reissuePw = null;
		PrintWriter reissueNoticePw = null;
		String invtNo = null;
		boolean copyFile = false;
		boolean copyNoticeFile = false;
		List<String> invtNoList = new ArrayList<String>();
		Gson gson = new Gson();
		if (null != invtList && !invtList.isEmpty()) {
			try {
				reissueFileName = sdf.format(Calendar.getInstance().getTime()) + suffix;

				reissueTmpFile = new File(this.app.getReissueTmpDir() + reissueFileName);
				reissueFile = new File(this.app.getReissueNoticeDir() + reissueFileName);
				reissuePw = new PrintWriter(reissueTmpFile);

				reissueNoticeTmpFile = new File(this.app.getReissueNoticeTmpDir() + reissueFileName);
				reissueNoticeFile = new File(this.app.getReissueNoticeDir() + reissueFileName);
				reissueNoticePw = new PrintWriter(reissueNoticeTmpFile);
				for (InvtHead ih : invtList) {
					invtNo = ih.getInvtNo();
					invtNoList.add(invtNo);
					LOGGER.info("invtNo=[" + invtNo + "] errorInvtMap=[" + gson.toJson(CommonConstant.getErrorInvtMap()) + "] noticeInvtMap=["
						+ gson.toJson(CommonConstant.getNoticeInvtMap()) + "]");
					if (CommonConstant.isReissue(invtNo)) {
						LOGGER.info("reissue====");
						CommonConstant.addInvtToMap(invtNo);
						reissuePw.println(invtNo);
						copyFile = true;
					} else {
						if (CommonConstant.isReissueNotice(invtNo)) {
							LOGGER.info("reissue notice====");
							CommonConstant.addInvtToNoticeMap(invtNo);
							reissueNoticePw.println(invtNo);
							copyNoticeFile = true;
						}
					}
				}
				CommonConstant.cleanErrorNoticeMap(invtNoList);
				reissuePw.flush();
				reissuePw.close();
				reissuePw = null;
				reissueNoticePw.flush();
				reissueNoticePw.close();
				reissueNoticePw = null;
				if (copyFile) {
				    LOGGER.info("copy [" + reissueTmpFile.getAbsolutePath() + "] ==> [" + reissueFile.getAbsolutePath() + "]");
					FileUtils.copyFile(reissueTmpFile, reissueFile);
				}
				if (copyNoticeFile) {
					LOGGER.info("copy notice[" + reissueNoticeTmpFile.getAbsolutePath() + "] ==> [" + reissueNoticeFile.getAbsolutePath() + "]");
					FileUtils.copyFile(reissueNoticeTmpFile, reissueNoticeFile);
				}
			} catch(Exception e) {
				e.printStackTrace();
				LOGGER.error("reissue error", e);
			} finally {
				if (null != reissuePw) {
					reissuePw.close();
				}

				if (null != reissueNoticePw) {
					reissueNoticePw.close();
				}
			}
		}
	}
	
	/**
	 * 每隔10分钟清空一下重复的清单号的清单
	 */
	//@Scheduled(fixedDelay = 600000)
	public void deleteRepeatInvtNo() throws Exception {
		List<InvtHead> invtHeadList = this.invtHeadService.getInvtHeadListByRepeatInvtNo();
		if (null != invtHeadList && !invtHeadList.isEmpty()) {
			for (InvtHead ih : invtHeadList) {
				if (0 == this.pubRtnService.countPubRtnByBizGuid(ih.getHeadGuid())) {
					this.invtHeadService.deleteInvtHeadByHeadGuid(ih.getHeadGuid());
				}
			}
		}
	}

	/**
	 * 每隔10分钟同步一下企业端的清单状态
	 * @throws Exception
	 */
	//@Scheduled(fixedDelay = 600000)
	public void syncInvtNoStatus() throws Exception {
		this.invtHeadService.syncInvtNoStatus("26", "800");
		this.invtHeadService.syncInvtNoStatus("24", "500");
	}
	
	//@Scheduled(cron = "0 0 */2 * * *")
	public void modifyInvtStatus() throws Exception {
		List<String> headGuidList = this.invtHeadService.getReleaseBackStaggeredInvtList();
		
		if (null != headGuidList && !headGuidList.isEmpty()) {
			for (String headGuid : headGuidList) {
				this.invtHeadService.updateInvtHeadStatus(headGuid, "100");
			}
		}
	}
	
	/**
	 * 每隔10分钟同步一下电子车牌号
	 * @throws Exception
	 */
	//@Scheduled(fixedDelay = 600000)
	public void checkServer() throws Exception {
		this.veHeadService.syncVeENo();
//		List<ServerSystem> serverSystemList = this.serverSystemService.getServerSystemList(new ServerSystem());
//		String url = null;
//		Mem mem = null;
//		CpuPerc cpuPerc = null;
//		List<FileSystemInfo> fileSystemInfoList = null;
//		for (ServerSystem ss : serverSystemList) {
//			url = "http://" + ss.getIp() + ":" + ss.getPort() + "/";
//			try {
//				mem = HttpClientTool.getMemJson(url);
//				if (80 < mem.getUsedPercent()) {
//					MailTool.sendDefaultMail(mailSender, "内存使用率超过85%", "服务器：[" + ss.getIp() + "]\r\n 内存使用率超过80%，请即时查看处理。");
//				}
//				
//				cpuPerc = HttpClientTool.getCpuPercJson(url);
//				if (0.2 > cpuPerc.getIdle()) {
//					MailTool.sendDefaultMail(mailSender, "CPU使用率超过85%", "服务器：[" + ss.getIp() + "]\r\n CPU使用率超过85%，请即时查看处理。");
//				}
//				
//				fileSystemInfoList = HttpClientTool.getFileSystemInfoListJson(url);
//				for (FileSystemInfo fsi : fileSystemInfoList) {
//					if (0.8 < fsi.getFileSystemUsage().getUsePercent()) {
//						MailTool.sendDefaultMail(mailSender, "硬盘:[" + fsi.getFileSystem().getDirName() + "] 使用率超过85%", "服务器：[" + ss.getIp() + "]\r\n 硬盘:[" 
//								+ fsi.getFileSystem().getDirName() + "]使用率超过85%，请即时查看处理。");
//					}
//				}
//			} catch (Exception e) {
//				e.printStackTrace();
//			}
//			
//		}
		
	}
	
	//@Scheduled(initialDelay = 10000, fixedDelay = 60000)
	public void syncPaymentInfo() throws Exception {
		SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMddHHmmss");
		SimpleDateFormat sdfDay = new SimpleDateFormat("yyyyMMdd");
		ImpPayHead insertImpPayHead = null;
		ImpPayHead searchImpPayHead = null;
		CbecMessage cbecMessage = null;
		CbecMessageCiq cbecMessageCiq = new CbecMessageCiq();
		MessageHeadCiq messageHeadCiq = new MessageHeadCiq();
		MessageBodyCiq messageBodyCiq = new MessageBodyCiq();
		BodyMasterCiq bodyMasterCiq = new BodyMasterCiq();
		PaymentMessage searchPm = new PaymentMessage();
		List<PaymentMessage> resultPaymentMessageList = null;
		Long lastSyncTime = this.syncPaymentInfoService.getSyncTime();

		if (null != lastSyncTime && 10000000000000L < lastSyncTime) {
			searchPm.setBeginCreateDate(String.valueOf(lastSyncTime));
		}

		Calendar yesterdayCalendar = Calendar.getInstance();
		yesterdayCalendar.add(Calendar.DAY_OF_YEAR, -1);
		searchPm.setBeginDateNum(Long.valueOf(sdfDay.format(yesterdayCalendar.getTime()) + "00"));
		searchPm.setXmlContent("<BILLMODE>0</BILLMODE>");
		searchPm.setOrderBy("created_date asc");
		resultPaymentMessageList = this.paymentMessageService.getPaymentMessageList(searchPm);

		if (null != resultPaymentMessageList && !resultPaymentMessageList.isEmpty()) {
			for (PaymentMessage pm : resultPaymentMessageList) {
				cbecMessage = PaymentTool.buildCbecMessageByString(pm.getXmlContent(), pm.getCreatedDate());
				insertImpPayHead = PaymentTool.buildImpPayHeadByCbecMessage(cbecMessage);

				if (null != insertImpPayHead) {
					try {
						searchImpPayHead = new ImpPayHead(insertImpPayHead.getUuid());
						if (0 < this.impPayHeadService.countImpPayHead(searchImpPayHead)) {
							continue;
						}

						BeanUtils.copyProperties(cbecMessage.getMessageBody().getBodyMaster(), bodyMasterCiq);
						BeanUtils.copyProperties(cbecMessage.getMessageHead(), messageHeadCiq);
						bodyMasterCiq.setCoinInsp(bodyMasterCiq.getMonetaryType());
						bodyMasterCiq.setMonetaryType(null);
						messageBodyCiq.setBodyMaster(bodyMasterCiq);
						cbecMessageCiq.setMessageHead(messageHeadCiq);
						cbecMessageCiq.setMessageBody(messageBodyCiq);

						PaymentTool.generateCbecMessageCiq(cbecMessageCiq, this.app.getCiqDir(), this.app.getBackDir());
						cbecMessage.getMessageBody().getBodyMaster().setPayEnterpriseCode("4100300536");
						cbecMessage.getMessageBody().getBodyMaster().setMonetaryType("156");
						PaymentTool.generateCbecMessageCiq(cbecMessage, this.app.getUnifiedCiqDir(), this.app.getUnifiedBackDir());

						searchImpPayHead = new ImpPayHead(insertImpPayHead.getPayCode(),
								insertImpPayHead.getPayTransactionId());
						if (1 == this.impPayHeadService.countImpPayHead(searchImpPayHead)) {
							this.impPayHeadService.updateImpPayHead(insertImpPayHead);
						} else {
							this.impPayHeadService.insertPayHead(insertImpPayHead);
							this.syncPaymentInfoService.updateSyncTime(Long.valueOf(sdf.format(pm.getCreatedDate())));
						}
					} catch (Exception e) {
						e.printStackTrace();
					}
				}
			}
		}
	}
}
