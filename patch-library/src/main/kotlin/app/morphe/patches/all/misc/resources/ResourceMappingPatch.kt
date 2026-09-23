@file:Suppress("unused", "DEPRECATION")

package app.morphe.patches.all.misc.resources

import app.morphe.patcher.InstructionLocation
import app.morphe.patcher.LiteralFilter
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.resourcePatch

@Deprecated(
    "Resource mappings are now provided by Morphe Patcher",
    ReplaceWith("ResourceType", "app.morphe.patcher.resource.ResourceType")
)
typealias ResourceType = app.morphe.patcher.resource.ResourceType

@Deprecated("Resource mappings are now provided by Morphe Patcher")
data class ResourceElement(val type: ResourceType, val name: String, val id: Long)

/**
 * @return A resource id of the given resource type and name.
 * @throws PatchException if the resource is not found.
 */
@Deprecated(
    "Resource mappings are now provided by Morphe Patcher",
    ReplaceWith("resourceId(type, name)", "app.morphe.patcher.resource.resourceId")
)
fun getResourceId(type: ResourceType, name: String): Long =
    app.morphe.patcher.resource.resourceId(type, name)

/**
 * @return If the resource exists.
 */
@Deprecated(
    "Resource mappings are now provided by Morphe Patcher",
    ReplaceWith(
        "hasResourceId(type, name)",
        "app.morphe.patcher.resource.hasResourceId"
    )
)
fun hasResourceId(type: ResourceType, name: String): Boolean =
    app.morphe.patcher.resource.hasResourceId(type, name)

@Deprecated(
    "Resource mappings are now provided by Morphe Patcher",
    ReplaceWith("LiteralFilter",
        "app.morphe.patcher.LiteralFilter")
)
typealias ResourceLiteralFilter = LiteralFilter

/**
 * Identical to [LiteralFilter] except uses a decoded resource literal value.
 *
 * @param exceptionIfResourceNotFound If false and the resource does not exist,
 *   then this filter effectively never matches anything. This should only be used
 *   with [app.morphe.patcher.anyInstruction] where one of the resource filters
 *   may not exist in all app versions.
 */
@Deprecated(
    "Resource mappings are now provided by Morphe Patcher",
    ReplaceWith(
        "resourceLiteral(type, name, exceptionIfResourceNotFound, location)",
        "app.morphe.patcher.resourceLiteral"
    )
)
fun resourceLiteral(
    type: ResourceType,
    name: String,
    exceptionIfResourceNotFound: Boolean = true,
    location: InstructionLocation = InstructionLocation.MatchAfterAnywhere()
) = app.morphe.patcher.resourceLiteral(type, name, exceptionIfResourceNotFound, location)

@Deprecated("Resource mappings are now provided by Morphe Patcher")
val resourceMappingPatch = resourcePatch(
    default = false
) {}
