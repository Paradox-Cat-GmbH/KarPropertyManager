package com.paradoxcat.karpropertymanager.model

class KarPropertyManagerException(
    cause: Exception,
) : Exception(cause)

class SubscriptionUnsuccessfulException : Exception()
