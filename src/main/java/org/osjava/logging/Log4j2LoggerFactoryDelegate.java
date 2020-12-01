/**
 * Copyright (C) 2000-2020 Atomikos <info@atomikos.com>
 *
 * LICENSE CONDITIONS
 *
 * See http://www.atomikos.com/Main/WhichLicenseApplies for details.
 */

package org.osjava.logging;

public class Log4j2LoggerFactoryDelegate implements LoggerFactoryDelegate {

	@Override
	public Logger createLogger(Class<?> clazz) {

		return new Log4j2Logger(clazz);
	}

	@Override
	public String toString() {

		return "Log4j2";
	}
}
