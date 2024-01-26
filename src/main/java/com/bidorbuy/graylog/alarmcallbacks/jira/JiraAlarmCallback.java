/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2016 Gerd Naschenweng / bidorbuy.co.za
 *
 * Original idea from https://github.com/tjackiw/graylog-plugin-jira
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 *
 */

package com.bidorbuy.graylog.alarmcallbacks.jira;

import java.math.BigInteger;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringEscapeUtils;
import org.apache.commons.lang3.StringUtils;
import org.graylog2.plugin.MessageSummary;
import org.graylog2.plugin.Tools;
import org.graylog2.plugin.alarms.AlertCondition;
import org.graylog2.plugin.alarms.callbacks.AlarmCallback;
import org.graylog2.plugin.alarms.callbacks.AlarmCallbackConfigurationException;
import org.graylog2.plugin.alarms.callbacks.AlarmCallbackException;
import org.graylog2.plugin.configuration.Configuration;
import org.graylog2.plugin.configuration.ConfigurationException;
import org.graylog2.plugin.configuration.ConfigurationRequest;
import org.graylog2.plugin.configuration.fields.BooleanField;
import org.graylog2.plugin.configuration.fields.ConfigurationField;
import org.graylog2.plugin.configuration.fields.TextField;
import org.graylog2.plugin.streams.Stream;
import org.graylog2.plugin.streams.StreamRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;

public class JiraAlarmCallback implements AlarmCallback
{
	// Configuration Constants
	public static final String CK_JIRA_INSTANCE_URL				= "jira_instance_url";
	public static final String CK_JIRA_USERNAME					= "jira_username";
	public static final String CK_JIRA_PASSWORD					= "jira_password";
	public static final String CK_JIRA_PROJECT_KEY				= "jira_project_key";
	public static final String CK_JIRA_TITLE_TEMPLATE			= "jira_title_template";
	public static final String CK_JIRA_ISSUE_TYPE				= "jira_issue_type";
	public static final String CK_JIRA_LABELS					= "jira_labels";
	public static final String CK_JIRA_PRIORITY					= "jira_priority";
	public static final String CK_JIRA_COMPONENTS				= "jira_components";
	public static final String CK_JIRA_MESSAGE_TEMPLATE			= "jira_message_template";
	public static final String CK_JIRA_MESSAGE_TEMPLATE_COMMENT	= "jira_message_template_comment";
	public static final String CK_JIRA_MD5_CUSTOM_FIELD			= "jira_md5_custom_field";
	public static final String CK_JIRA_MD5_HASH_PATTERN			= "jira_md5_hash_pattern";
	public static final String CK_JIRA_MD5_HISTORY				= "jira_md5_history";
	public static final String CK_JIRA_MD5_FILTER_QUERY			= "jira_md5_filter_query";
	public static final String CK_JIRA_GRAYLOG_MAPPING			= "jira_graylog_message_field_mapping";

	public static final String CK_JIRA_COUNTER_CUSTOM_FIELD		= "jira_counter_custom_field";

	public static final String CK_JIRA_COUNTER_CUSTOM_FIELD_DESC = "JIRA Counter custom field";

	public static final String CK_GRAYLOG_URL = "graylog_url";
	public static final String CK_MESSAGE_REGEX = "message_regex";

	// Validation rules for config check
	private static final List<String> SENSITIVE_CONFIGURATION_KEYS = ImmutableList.of(CK_JIRA_PASSWORD);

	private static final String[] CONFIGURATION_KEYS_MANDATORY =
	{
		CK_JIRA_INSTANCE_URL,
		CK_JIRA_USERNAME,
		CK_JIRA_PASSWORD,
		CK_JIRA_PROJECT_KEY,
		CK_JIRA_ISSUE_TYPE
	};

	private static final String[] CONFIGURATION_KEYS_URL_VALIDATION = { CK_JIRA_INSTANCE_URL, CK_GRAYLOG_URL };

	// The default template for JIRA messages
	private static final String CONST_JIRA_MESSAGE_TEMPLATE = "[STREAM_RESULT]\n\n *Stream title:* \n [STREAM_TITLE]\n\n"
			+ " *Stream URL:* \n [STREAM_URL]\n\n" + " *Stream rules:* \n [STREAM_RULES]\n\n"
			+ " *Alert triggered at:* \n [ALERT_TRIGGERED_AT]\n\n"
			+ " *Triggered condition:* \n [ALERT_TRIGGERED_CONDITION]\n\n" + " *Source:* \n [LAST_MESSAGE.source]\n\n"
			+ " *Message:* \n [LAST_MESSAGE.message]\n\n";

	// The default title template for JIRA messages
	private static final String CONST_JIRA_TITLE_TEMPLATE = "Jira [MESSAGE_REGEX]";

	// The message regex template used to extract content for an exception MD5
	private static final String CONST_JIRA_MESSAGE_REGEX = "([a-zA-Z_.]+(?!.*Exception): .+)";

	// The default MD5 template
	private static final String CONST_JIRA_MD5_TEMPLATE = "[MESSAGE_REGEX]";
	private static final String CONST_JIRA_MD5_FILTER_QUERY_TEMPLATE = "AND Status not in (Closed, Done, Resolved)";

	// The logger
	private static final Logger LOG = LoggerFactory.getLogger(JiraAlarmCallback.class);

	// The plugin configuration
	private Configuration configuration;

	/*
	 * This is called once at the very beginning of the lifecycle of this plugin. It
	 * is common practice to store the Configuration as a private member for later
	 * access.
	 *
	 * @see
	 * org.graylog2.plugin.alarms.callbacks.AlarmCallback#initialize(org.graylog2.
	 * plugin.configuration.Configuration)
	 */
	@Override
	public void initialize(final Configuration config) throws AlarmCallbackConfigurationException
	{
		this.configuration = config;
	}

	/*
	 * This is the actual alarm callback being triggered.
	 *
	 * @see
	 * org.graylog2.plugin.alarms.callbacks.AlarmCallback#call(org.graylog2.plugin.
	 * streams.Stream, org.graylog2.plugin.alarms.AlertCondition.CheckResult)
	 */
	@Override
	public void call(final Stream stream, final AlertCondition.CheckResult result) throws AlarmCallbackException
	{
		call(
				new JiraIssueClient(
					configuration.getString(CK_JIRA_PROJECT_KEY),
					buildJIRATitle(stream, result),
					buildDescription(stream, result),
					configuration.getBoolean(CK_JIRA_MESSAGE_TEMPLATE_COMMENT),
					configuration.getString(CK_JIRA_LABELS),
					configuration.getString(CK_JIRA_ISSUE_TYPE),
					configuration.getString(CK_JIRA_COMPONENTS),
					configuration.getString(CK_JIRA_PRIORITY),
					configuration.getString(CK_JIRA_INSTANCE_URL),
					configuration.getString(CK_JIRA_USERNAME),
					configuration.getString(CK_JIRA_PASSWORD),
					configuration.getString(CK_JIRA_MD5_FILTER_QUERY),
					configuration.getString(CK_JIRA_MD5_CUSTOM_FIELD),
					configuration.getBoolean(CK_JIRA_MD5_HISTORY),
					configuration.getString(CK_JIRA_COUNTER_CUSTOM_FIELD),
					buildJIRAGraylogMapping(stream, result),
					getJIRAMessageDigest(stream, result)
				),
				stream,
				result);
	}

	/**
	 * Trigger the event
	 *
	 * @param client
	 * @param stream
	 * @param result
	 * @throws AlarmCallbackException
	 */
	void call(final JiraIssueClient client, final Stream stream, final AlertCondition.CheckResult result)
			throws AlarmCallbackException
	{
		client.trigger(stream, result);
	}

	/*
	 * Plugins can request configurations. The UI in the Graylog web interface is
	 * generated from this information and the filled out configuration values are
	 * passed back to the plugin in initialize(Configuration configuration).
	 *
	 * @see
	 * org.graylog2.plugin.alarms.callbacks.AlarmCallback#getRequestedConfiguration(
	 * )
	 */
	@Override
	public ConfigurationRequest getRequestedConfiguration()
	{
		final ConfigurationRequest configurationRequest = new ConfigurationRequest();

		configurationRequest.addField(new TextField(
				CK_JIRA_INSTANCE_URL, "JIRA Instance URL", "", "JIRA server URL.",
				ConfigurationField.Optional.NOT_OPTIONAL));

		configurationRequest.addField(new TextField(
				CK_JIRA_USERNAME, "JIRA username", "", "Username to login to JIRA and create issues.",
				ConfigurationField.Optional.NOT_OPTIONAL));

		configurationRequest.addField(new TextField(
				CK_JIRA_PASSWORD, "JIRA password", "", "Password to login to JIRA.",
				ConfigurationField.Optional.NOT_OPTIONAL, TextField.Attribute.IS_PASSWORD));

		configurationRequest.addField(new TextField(
				CK_JIRA_PROJECT_KEY, "JIRA project Key", "", "Project under which the issue will be created.",
				ConfigurationField.Optional.NOT_OPTIONAL));

		configurationRequest.addField(new TextField(
				CK_JIRA_ISSUE_TYPE, "JIRA issue Type", "Bug", "Type of issue.",
				ConfigurationField.Optional.NOT_OPTIONAL));

		configurationRequest.addField(new TextField(
				CK_JIRA_MESSAGE_TEMPLATE, "JIRA message template", CONST_JIRA_MESSAGE_TEMPLATE.replaceAll("\n", "\\\n"),
				"Message template for JIRA", ConfigurationField.Optional.NOT_OPTIONAL));

		configurationRequest.addField(new BooleanField(
				CK_JIRA_MESSAGE_TEMPLATE_COMMENT, "JIRA message template as comments", true,
				"Check if you want your message template to be added as a JIRA comment if there is already a JIRA issue"
				+ " matching this MD5. You would typically check this on if your message template carries "
				+ "troubleshooting information that is different from one occurrence to the next."));

		configurationRequest.addField(new TextField(
				CK_JIRA_TITLE_TEMPLATE, "JIRA issue title template", CONST_JIRA_TITLE_TEMPLATE,
				"Title template for JIRA tasks", ConfigurationField.Optional.NOT_OPTIONAL));

		configurationRequest.addField(new TextField(
				CK_GRAYLOG_URL, "Graylog URL", null,
				"URL to your Graylog web interface. Used to build links in alarm notification.",
				ConfigurationField.Optional.NOT_OPTIONAL));

		configurationRequest.addField(new TextField(
				CK_JIRA_PRIORITY, "JIRA Issue Priority", "Minor", "Priority of the issue.",
				ConfigurationField.Optional.OPTIONAL));

		configurationRequest.addField(new TextField(
				CK_JIRA_LABELS, "JIRA Labels", "", "List of comma-separated labels to add to this issue - i.e. graylog",
				ConfigurationField.Optional.OPTIONAL));

		configurationRequest.addField(new TextField(
				CK_JIRA_COMPONENTS, "JIRA Components", "", "List of comma-separated components to add to this issue.",
				ConfigurationField.Optional.OPTIONAL));

		configurationRequest.addField(new TextField(
				CK_MESSAGE_REGEX, "Message regex", "",
				"Message regex to extract message content. Example: " + CONST_JIRA_MESSAGE_REGEX,
				ConfigurationField.Optional.OPTIONAL));

		configurationRequest.addField(new TextField(
				CK_JIRA_MD5_HASH_PATTERN, "JIRA MD5 pattern", "",
				"Pattern to construct MD5. Example: " + CONST_JIRA_MD5_TEMPLATE, ConfigurationField.Optional.OPTIONAL));

		configurationRequest.addField(new BooleanField(
				CK_JIRA_MD5_HISTORY, "JIRA MD5 History", false,
				"If this option is checked, then upon creating a new JIRA issue for a given MD5, a list of all "
				+ "previous JIRA issues (irrespective of their states) will be put in the JIRA description of the "
				+ "new JIRA issue. This can be used as an indication that a problem has not been properly fixed as "
				+ "it keeps reappearing."));

		configurationRequest.addField(new TextField(
				CK_JIRA_MD5_CUSTOM_FIELD, "JIRA MD5 custom field", "",
				"Custom field name for the MD5 hash, this will be in the format of customfield_#### where '####' is an"
				+ " integer value. If not set, we will try and find it",
				ConfigurationField.Optional.OPTIONAL));

		configurationRequest.addField(new TextField(
				CK_JIRA_COUNTER_CUSTOM_FIELD, CK_JIRA_COUNTER_CUSTOM_FIELD_DESC, "10200",
				"Custom field name for the counter, this will be in the format of customfield_#### where '####' is an"
				+ " integer value. If not set, the counter functionality will be disabled.",
				ConfigurationField.Optional.OPTIONAL));

		configurationRequest.addField(new TextField(
				CK_JIRA_MD5_FILTER_QUERY, "JIRA duplicate filter query", "",
				"Additional filter query to check for duplicates. Example: " + CONST_JIRA_MD5_FILTER_QUERY_TEMPLATE,
				ConfigurationField.Optional.OPTIONAL));

		configurationRequest.addField(new TextField(
				CK_JIRA_GRAYLOG_MAPPING, "JIRA/Graylog field mapping", "",
				"List of comma-separated Graylog/JIRA mapping fields to automatically map Graylog message fields into JIRA",
				ConfigurationField.Optional.OPTIONAL));

		return configurationRequest;
	}

	/*
	 * Return attributes that might be interesting to be shown under the alarm
	 * callback in the Graylog web interface. It is common practice to at least
	 * return the used configuration here.
	 *
	 * @see org.graylog2.plugin.alarms.callbacks.AlarmCallback#getAttributes()
	 */
	@Override
	public Map<String, Object> getAttributes()
	{
		return Maps.transformEntries(configuration.getSource(), new Maps.EntryTransformer<String, Object, Object>()
		{
			@Override
			public Object transformEntry(String key, Object value)
			{
				if (SENSITIVE_CONFIGURATION_KEYS.contains(key))
				{
					return "****";
				}
				return value;
			}
		});
	}

	/*
	 * Throw a ConfigurationException if the user should have entered missing or
	 * invalid configuration parameters.
	 *
	 * @see org.graylog2.plugin.alarms.callbacks.AlarmCallback#checkConfiguration()
	 */
	@Override
	public void checkConfiguration() throws ConfigurationException
	{
		// Check if we have all mandatory keys
		for (String key : CONFIGURATION_KEYS_MANDATORY)
		{
			if (!configuration.stringIsSet(key))
			{
				throw new ConfigurationException(key + " is mandatory and must not be empty.");
			}
		}

		// Check if the provided URLs are valid
		for (String key : CONFIGURATION_KEYS_URL_VALIDATION)
		{
			if (configuration.stringIsSet(key) && !configuration.getString(key).equals("null"))
			{
				try
				{
					final URI configURI = new URI(configuration.getString(key));
					if (!"http".equals(configURI.getScheme()) && !"https".equals(configURI.getScheme()))
					{
						throw new ConfigurationException(key + " must be a valid HTTP or HTTPS URL.");
					}
				}
				catch (URISyntaxException e)
				{
					throw new ConfigurationException("Couldn't parse " + key + " correctly.", e);
				}
			}
		}
	}

	/*
	 * Return a human readable name of this plugin.
	 *
	 * @see org.graylog2.plugin.alarms.callbacks.AlarmCallback#getName()
	 */
	@Override
	public String getName()
	{
		return "Graylog JIRA integration plugin";
	}

	/**
	 * Generates the MD5 digest of either the message or a number of fields provided
	 *
	 * @param stream
	 * @param checkResult
	 * @return
	 */
	private String getJIRAMessageDigest(final Stream stream, final AlertCondition.CheckResult result)
	{
		String JiraMessageDigest = "";

		// Get the last message
		if (!result.getMatchingMessages().isEmpty())
		{
			String JiraMessageRegex = "";
			MessageSummary lastMessage = result.getMatchingMessages().get(0);

			// Let's extract the message regex first
			if (configuration.stringIsSet(CK_MESSAGE_REGEX)
					&& !configuration.getString(CK_MESSAGE_REGEX).equals("null"))
			{
				try
				{
					Matcher matcher = Pattern.compile(configuration.getString(CK_MESSAGE_REGEX))
							.matcher(lastMessage.getMessage());

					if (matcher.find())
					{
						JiraMessageRegex = lastMessage.getMessage().substring(matcher.start());
					}
				}
				catch (Exception ex)
				{
					LOG.warn("Error in JIRA-issue MD5-MESSAGE_REGEX generation: " + ex.getMessage());
				}
			}

			String JiraMD5Content = "";

			// Let's extract the message regex first
			if (configuration.stringIsSet(CK_JIRA_MD5_HASH_PATTERN)
					&& !configuration.getString(CK_JIRA_MD5_HASH_PATTERN).equals("null"))
			{

				try
				{
					JiraMD5Content = configuration.getString(CK_JIRA_MD5_HASH_PATTERN);

					// replace the message-regex place-holder
					JiraMD5Content = JiraMD5Content.replace("[MESSAGE_REGEX]", JiraMessageRegex);

					// iterate through all the message fields and replace the template
					Map<String, Object> lastMessageFields = lastMessage.getFields();

					for (Map.Entry<String, Object> arg : lastMessageFields.entrySet())
					{
						JiraMD5Content = JiraMD5Content.replace("[LAST_MESSAGE." + arg.getKey() + "]",
								arg.getValue().toString());
					}

					// We regex template fields which have not been replaced
					JiraMD5Content = JiraMD5Content.replaceAll("\\[LAST_MESSAGE\\.[^\\]]*\\]", "");
				}
				catch (Exception ex)
				{
					LOG.warn("Error in JIRA-issue MD5-HASH_PATTERN generation: " + ex.getMessage());
				}
			}

			// We default the extracted message as the template
			if (StringUtils.isBlank(JiraMD5Content))
			{
				JiraMD5Content = JiraMessageRegex;
			}

			// Create the MD5 from the template
			if (StringUtils.isNotBlank(JiraMD5Content))
			{
				try
				{
					MessageDigest m = MessageDigest.getInstance("MD5");
					m.update(JiraMD5Content.getBytes(), 0, JiraMD5Content.length());
					JiraMessageDigest = new BigInteger(1, m.digest()).toString(16);
				}
				catch (Exception ex)
				{
					LOG.warn("Error in JIRA-issue MD5 generation (MD5-string=" + JiraMD5Content + "): "
							+ ex.getMessage());
				}
			}
			else
			{
				LOG.warn("Skipped MD5-hash creation, MD5-string is empty. Check your config");
			}
		}
		else
		{
			LOG.warn("Skipping JIRA-issue MD5 generation, alarmcallback did not provide a message");
		}

		return JiraMessageDigest;
	}

	/**
	 * Build the JIRA issue title
	 *
	 * @param stream
	 * @param result
	 * @return
	 */
	private String buildJIRATitle(final Stream stream, final AlertCondition.CheckResult result)
	{
		StringBuilder sb = new StringBuilder();

		try
		{
			if (!result.getMatchingMessages().isEmpty())
			{
				// get fields from last message only
				MessageSummary lastMessage = result.getMatchingMessages().get(0);

				Map<String, Object> lastMessageFields = lastMessage.getFields();

				String strTitle = "[Alert] Graylog alert for stream: " + stream.getTitle();

				if (configuration.stringIsSet(CK_JIRA_TITLE_TEMPLATE)
						&& !configuration.getString(CK_JIRA_TITLE_TEMPLATE).equals("null"))
				{
					strTitle = configuration.getString(CK_JIRA_TITLE_TEMPLATE);
				}

				strTitle = strTitle.replace("[LAST_MESSAGE.source]", lastMessage.getSource());

				for (Map.Entry<String, Object> arg : lastMessageFields.entrySet())
				{
					strTitle = strTitle.replace("[LAST_MESSAGE." + arg.getKey() + "]", arg.getValue().toString());
				}

				if (configuration.stringIsSet(CK_MESSAGE_REGEX)
						&& !configuration.getString(CK_MESSAGE_REGEX).equals("null"))
				{
					Matcher matcher = Pattern.compile(configuration.getString(CK_MESSAGE_REGEX))
							.matcher(lastMessage.getMessage());

					if (matcher.find())
					{
						if (configuration.stringIsSet(CK_JIRA_TITLE_TEMPLATE)
								&& !configuration.getString(CK_JIRA_TITLE_TEMPLATE).equals("null"))
						{
							strTitle = strTitle.replace("[MESSAGE_REGEX]", matcher.group());
						}
						else
						{
							strTitle = "[Graylog] " + matcher.group();
						}
					}
				}

				// We regex template fields which have not been replaced
				strTitle = strTitle.replaceAll("\\[LAST_MESSAGE\\.[^\\]]*\\]", "");

				sb.append(strTitle);
			}
		}
		catch (Exception ex)
		{
			; // can not do anything - we skip
			LOG.error("Error in building title: " + ex.getMessage());
		}

		if (sb.length() == 0)
		{
			sb.append("[Alert] Graylog alert for stream: ").append(stream.getTitle());
		}

		return sb.toString();
	}

	/**
	 * Build the JIRA description
	 *
	 * @param stream
	 * @param result
	 * @return
	 */
	private String buildDescription(final Stream stream, final AlertCondition.CheckResult result)
	{
		String strMessage = CONST_JIRA_MESSAGE_TEMPLATE;

		if (configuration.stringIsSet(CK_JIRA_MESSAGE_TEMPLATE)
				&& !configuration.getString(CK_JIRA_MESSAGE_TEMPLATE).equals("null")
				&& !configuration.getString(CK_JIRA_MESSAGE_TEMPLATE).isEmpty())
		{
			strMessage = configuration.getString(CK_JIRA_MESSAGE_TEMPLATE);
		}

		strMessage = StringEscapeUtils.unescapeJava(strMessage);

		// Get the last message
		if (!result.getMatchingMessages().isEmpty())
		{
			// get fields from last message only
			MessageSummary lastMessage = result.getMatchingMessages().get(0);
			Map<String, Object> lastMessageFields = lastMessage.getFields();

			strMessage = strMessage.replace("[LAST_MESSAGE.message]", lastMessage.getMessage());
			strMessage = strMessage.replace("[LAST_MESSAGE.source]", lastMessage.getSource());

			for (Map.Entry<String, Object> arg : lastMessageFields.entrySet())
			{
				strMessage = strMessage.replace("[LAST_MESSAGE." + arg.getKey() + "]", arg.getValue().toString());
			}

			// We regex template fields which have not been replaced
			strMessage = strMessage.replaceAll("\\[LAST_MESSAGE\\.[^\\]]*\\]", "");
		}

		// replace placeholders
		strMessage = strMessage.replace("[CALLBACK_DATE]", Tools.iso8601().toString());
		strMessage = strMessage.replace("[STREAM_ID]", stream.getId());
		strMessage = strMessage.replace("[STREAM_TITLE]", stream.getTitle());
		strMessage = strMessage.replace("[STREAM_URL]",
				buildStreamURL(configuration.getString(CK_GRAYLOG_URL), stream));
		strMessage = strMessage.replace("[STREAM_RULES]", buildStreamRules(stream));
		strMessage = strMessage.replace("[STREAM_RESULT]", result.getResultDescription());
		strMessage = strMessage.replace("[ALERT_TRIGGERED_AT]", result.getTriggeredAt().toString());
		strMessage = strMessage.replace("[ALERT_TRIGGERED_CONDITION]", result.getTriggeredCondition().toString());

		// create final string
		StringBuilder sb = new StringBuilder();
		sb.append("\n\n");
		sb.append(strMessage).append("\n\n");

		return sb.toString();
	}

	/**
	 * Build stream URL string
	 *
	 * @param baseUrl
	 * @param stream
	 * @return
	 */
	protected String buildStreamURL(final String configURL, final Stream stream)
	{
		String baseUrl = configURL;

		if (!baseUrl.endsWith("/"))
		{
			baseUrl += "/";
		}

		return baseUrl + "streams/" + stream.getId() + "/messages?q=*&rangetype=relative&relative=3600";
	}

	/**
	 * Build the stream rules
	 *
	 * @param stream
	 * @return
	 */
	protected String buildStreamRules(final Stream stream)
	{

		StringBuilder sb = new StringBuilder();

		for (StreamRule streamRule : stream.getStreamRules())
		{
			sb.append("_").append(streamRule.getField()).append("_ ");
			sb.append(streamRule.getType()).append(" _").append(streamRule.getValue()).append("_").append("\n");
		}
		return sb.toString();
	}

	/**
	 * Build up a list of JIRA/Graylog field mappings
	 *
	 * @param stream
	 * @param result
	 * @return
	 */
	private Map<String, String> buildJIRAGraylogMapping(final Stream stream, final AlertCondition.CheckResult result)
	{
		Map<String, String> JIRAFieldMapping = new HashMap<String, String>();

		if (configuration.stringIsSet(CK_JIRA_GRAYLOG_MAPPING)
				&& !configuration.getString(CK_JIRA_GRAYLOG_MAPPING).equals("null")
				&& !result.getMatchingMessages().isEmpty())
		{
			try
			{
				// get fields from last message only
				MessageSummary lastMessage = result.getMatchingMessages().get(0);

				String[] mappingPairs = StringUtils.split(configuration.getString(CK_JIRA_GRAYLOG_MAPPING), ',');

				if (mappingPairs != null && mappingPairs.length > 0)
				{
					for (String mappingString : mappingPairs)
					{
						String[] mapping = StringUtils.split(mappingString, '=');

						if (mapping.length == 2 && lastMessage.hasField(mapping[0]))
						{
							Object test = lastMessage.getField(mapping[0]);
							JIRAFieldMapping.put(mapping[1], test.toString());
						}
					}
				}
			}
			catch (Exception ex)
			{
				; // can not do anything - we skip
				LOG.error("Error in generating JIRA/Graylog mapping " + ex.getMessage());
			}
		}

		return JIRAFieldMapping;
	}
}
