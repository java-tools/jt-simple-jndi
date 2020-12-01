/**
 * Copyright (C) 2000-2020 Atomikos <info@atomikos.com>
 *
 * LICENSE CONDITIONS
 *
 * See http://www.atomikos.com/Main/WhichLicenseApplies for details.
 */

package org.osjava.logging;

class Log4JLoggerFactoryDelegate implements LoggerFactoryDelegate {

	public Logger createLogger(Class<?> clazz) {

		return new Log4JLogger(clazz);
	}

	@Override
	public String toString() {

		return "Log4j";
	}
}
