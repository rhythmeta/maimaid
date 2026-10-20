package org.rhythmeta.maimaid.core.update

class AppUpdateException(val failure: AppUpdateFailure) : Exception(failure.name)
