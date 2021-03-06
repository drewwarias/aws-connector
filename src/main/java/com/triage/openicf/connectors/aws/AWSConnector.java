/*
 * DO NOT REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2016 ForgeRock AS. All rights reserved.
 *
 * The contents of this file are subject to the terms
 * of the Common Development and Distribution License
 * (the License). You may not use this file except in
 * compliance with the License.
 *
 * You can obtain a copy of the License at
 * http://opensource.org/licenses/CDDL-1.0
 * See the License for the specific language governing
 * permission and limitations under the License.
 *
 * When distributing Covered Code, include this CDDL
 * Header Notice in each file and include the License file
 * at http://opensource.org/licenses/CDDL-1.0
 * If applicable, add the following below the CDDL Header,
 * with the fields enclosed by brackets [] replaced by
 * your own identifying information:
 * "Portions Copyrighted [year] [name of copyright owner]"
 */

package com.triage.openicf.connectors.aws;

import java.util.Locale;
import java.util.Set;

import org.identityconnectors.common.logging.Log;
import org.identityconnectors.framework.api.ConnectorFacade;
import org.identityconnectors.framework.common.exceptions.ConnectorException;
import org.identityconnectors.framework.common.exceptions.InvalidAttributeValueException;
import org.identityconnectors.framework.common.objects.Attribute;
import org.identityconnectors.framework.common.objects.AttributeInfoBuilder;
import org.identityconnectors.framework.common.objects.AttributeUtil;
import org.identityconnectors.framework.common.objects.AttributesAccessor;
import org.identityconnectors.framework.common.objects.Name;
import org.identityconnectors.framework.common.objects.ObjectClass;
import org.identityconnectors.framework.common.objects.ObjectClassInfo;
import org.identityconnectors.framework.common.objects.ObjectClassInfoBuilder;
import org.identityconnectors.framework.common.objects.OperationOptions;
import org.identityconnectors.framework.common.objects.OperationOptionsBuilder;
import org.identityconnectors.framework.common.objects.ResultsHandler;
import org.identityconnectors.framework.common.objects.Schema;
import org.identityconnectors.framework.common.objects.SchemaBuilder;
import org.identityconnectors.framework.common.objects.Uid;
import org.identityconnectors.framework.common.objects.filter.FilterTranslator;
import org.identityconnectors.framework.spi.Configuration;
import org.identityconnectors.framework.spi.Connector;
import org.identityconnectors.framework.spi.ConnectorClass;
import org.identityconnectors.framework.spi.operations.CreateOp;
import org.identityconnectors.framework.spi.operations.DeleteOp;
import org.identityconnectors.framework.spi.operations.SchemaOp;
import org.identityconnectors.framework.spi.operations.SearchOp;
import org.identityconnectors.framework.spi.operations.TestOp;
import org.identityconnectors.framework.spi.operations.UpdateOp;

import com.triage.openicf.connectors.aws.client.AWSClient;
import com.triage.openicf.connectors.aws.operations.AWSUserOps;

/**
 * Main implementation of the AWS Connector.
 *
 */
@ConnectorClass(displayNameKey = "AWS.connector.display", configurationClass = AWSConfiguration.class)
public class AWSConnector implements Connector, CreateOp, DeleteOp, SearchOp<String>, TestOp, UpdateOp, SchemaOp {

	/**
	 * Setup logging for the {@link AWSConnector}.
	 */
	private static final Log logger = Log.getLog(AWSConnector.class);

	/**
	 * Place holder for the {@link Configuration} passed into the init() method
	 * {@link AWSConnector#init(org.identityconnectors.framework.spi.Configuration)}.
	 */
	private AWSConfiguration configuration;

	private Schema schema = null;
	private AWSClient client = null;
	private AWSUserOps userOps = null;

	/**
	 * Gets the Configuration context for this connector.
	 *
	 * @return The current {@link Configuration}
	 */
	public Configuration getConfiguration() {
		return this.configuration;
	}

	/**
	 * Callback method to receive the {@link Configuration}.
	 *
	 * @param configuration
	 *            the new {@link Configuration}
	 * @see org.identityconnectors.framework.spi.Connector#init(org.identityconnectors.framework.spi.Configuration)
	 */
	public void init(final Configuration configuration) {
		this.configuration = (AWSConfiguration) configuration;
		userOps = new AWSUserOps(this);
	}

	/**
	 * Disposes of the {@link AWSConnector}'s resources.
	 *
	 * @see org.identityconnectors.framework.spi.Connector#dispose()
	 */
	public void dispose() {
		configuration = null;
	}

	/******************
	 * SPI Operations
	 *
	 * Implement the following operations using the contract and description
	 * found in the Javadoc for these methods.
	 ******************/

	/**
	 * {@inheritDoc}
	 */
	public Uid create(final ObjectClass objectClass, final Set<Attribute> createAttributes,
			final OperationOptions options) {
		if (ObjectClass.ACCOUNT.equals(objectClass) || ObjectClass.GROUP.equals(objectClass)) {
			Name name = AttributeUtil.getNameFromAttributes(createAttributes);
			if (name != null) {
				return userOps.createUser(AttributeUtil.getStringValue(name), createAttributes.toString());
			} else {
				throw new InvalidAttributeValueException("Name attribute is required");
			}
		} else {
			logger.warn("Create of type {0} is not supported", configuration.getConnectorMessages()
					.format(objectClass.getDisplayNameKey(), objectClass.getObjectClassValue()));
			throw new UnsupportedOperationException(
					"Create of type" + objectClass.getObjectClassValue() + " is not supported");
		}
	}

	/**
	 * {@inheritDoc}
	 */
	public void delete(final ObjectClass objectClass, final Uid uid, final OperationOptions options) {
		if (ObjectClass.ACCOUNT.equals(objectClass) || ObjectClass.GROUP.equals(objectClass)) {
			userOps.deleteUser(uid.getUidValue());
		} else {
			logger.warn("Delete of type {0} is not supported", configuration.getConnectorMessages()
					.format(objectClass.getDisplayNameKey(), objectClass.getObjectClassValue()));
			throw new UnsupportedOperationException(
					"Delete of type" + objectClass.getObjectClassValue() + " is not supported");
		}
	}

	/**
	 * {@inheritDoc}
	 */
	public FilterTranslator<String> createFilterTranslator(ObjectClass objectClass, OperationOptions options) {
		return new AWSFilterTranslator();
	}

	/**
	 * {@inheritDoc}
	 */
	public void executeQuery(ObjectClass objectClass, String query, ResultsHandler handler, OperationOptions options) {
		if(objectClass.equals(ObjectClass.ACCOUNT)){
			userOps.queryUser(query, handler, options);
		}
	}

	/**
	 * {@inheritDoc}
	 */
	public void test() {
		try {
			client = new AWSClient(configuration);
			client.testConnection();
		} catch (Exception e) {
			logger.warn(e, "Test failed failed");
			throw new ConnectorException("Test failed failed", e);
		}
	}

	/**
	 * {@inheritDoc}
	 */
	public Uid update(ObjectClass objectClass, Uid uid, Set<Attribute> replaceAttributes, OperationOptions options) {
		AttributesAccessor attributesAccessor = new AttributesAccessor(replaceAttributes);
		Name newName = attributesAccessor.getName();
		Uid uidAfterUpdate = uid;
		if (newName != null) {
			logger.info("Rename the object {0}:{1} to {2}", objectClass.getObjectClassValue(), uid.getUidValue(),
					newName.getNameValue());
			uidAfterUpdate = new Uid(newName.getNameValue().toLowerCase(Locale.US));
		}

		if (ObjectClass.ACCOUNT.equals(objectClass)) {
			userOps.upgradeUser(uid.getName());
		} else if (ObjectClass.GROUP.is(objectClass.getObjectClassValue())) {
			if (attributesAccessor.hasAttribute("members")) {
				throw new InvalidAttributeValueException("Requested to update a read only attribute");
			}
		} else {
			logger.warn("Update of type {0} is not supported", configuration.getConnectorMessages()
					.format(objectClass.getDisplayNameKey(), objectClass.getObjectClassValue()));
			throw new UnsupportedOperationException(
					"Update of type" + objectClass.getObjectClassValue() + " is not supported");
		}
		return uidAfterUpdate;
	}

	public void buildSchema() {
		logger.info("Building Schema");
		final SchemaBuilder schemaBuilder = new SchemaBuilder(AWSConnector.class);
		ObjectClassInfoBuilder builder = new ObjectClassInfoBuilder();
		builder.setType(ObjectClass.ACCOUNT_NAME);
		builder.addAttributeInfo(Name.INFO);
		builder.addAttributeInfo(AttributeInfoBuilder.define("userName").setRequired(true).build());
		ObjectClassInfo oci = builder.build();
		schemaBuilder.defineObjectClass(oci);
		this.schema = schemaBuilder.build();
	}

	public Schema schema() {
		if (null == schema) {
			buildSchema();
		}
		return schema;
	}
}
