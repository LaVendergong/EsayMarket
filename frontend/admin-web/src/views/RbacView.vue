<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { rbacApi } from '@/api'
import { auth } from '@/stores/auth'
import type { AdminUserVO, PermissionVO, RoleVO } from '@/types'

const tab = ref('roles')
const roles = ref<RoleVO[]>([])
const permissions = ref<PermissionVO[]>([])
const admins = ref<AdminUserVO[]>([])
const loading = ref(false)

/** 权限按分组展示，勾选更直观 */
const grouped = computed(() => {
  const map = new Map<string, PermissionVO[]>()
  for (const p of permissions.value) {
    if (!map.has(p.permGroup)) map.set(p.permGroup, [])
    map.get(p.permGroup)!.push(p)
  }
  return [...map.entries()].map(([group, items]) => ({ group, items }))
})

const roleDialog = ref(false)
const roleForm = reactive({ id: 0, code: '', name: '', description: '', status: 1 })
const roleSaving = ref(false)

const permDrawer = ref(false)
const permTarget = ref<RoleVO | null>(null)
const checkedPerms = ref<number[]>([])
const permSaving = ref(false)

const userDialog = ref(false)
const userTarget = ref<AdminUserVO | null>(null)
const userRoleIds = ref<number[]>([])
const userSaving = ref(false)

async function load() {
  loading.value = true
  try {
    const [r, p, a] = await Promise.all([rbacApi.roles(), rbacApi.permissions(), rbacApi.admins()])
    roles.value = r
    permissions.value = p
    admins.value = a
  } finally {
    loading.value = false
  }
}

function openRole(row?: RoleVO) {
  Object.assign(roleForm, row
    ? { id: row.id, code: row.code, name: row.name, description: row.description ?? '', status: row.status }
    : { id: 0, code: '', name: '', description: '', status: 1 })
  roleDialog.value = true
}

async function saveRole() {
  if (!roleForm.code.trim() || !roleForm.name.trim()) {
    ElMessage.warning('角色标识和名称必填')
    return
  }
  roleSaving.value = true
  try {
    const dto = {
      code: roleForm.code.trim(),
      name: roleForm.name.trim(),
      description: roleForm.description.trim() || undefined,
      status: roleForm.status
    }
    if (roleForm.id) await rbacApi.updateRole(roleForm.id, dto)
    else await rbacApi.createRole(dto)
    ElMessage.success('已保存')
    roleDialog.value = false
    await load()
  } catch {
    // 拦截器已提示
  } finally {
    roleSaving.value = false
  }
}

async function removeRole(row: RoleVO) {
  try {
    await ElMessageBox.confirm(`确定删除角色「${row.name}」？`, '提示', { type: 'warning' })
  } catch {
    return
  }
  try {
    await rbacApi.deleteRole(row.id)
    ElMessage.success('已删除')
    await load()
  } catch {
    // 内置角色 / 还有人在用，后端会拒绝并提示
  }
}

function openPermissions(row: RoleVO) {
  permTarget.value = row
  checkedPerms.value = [...row.permissionIds]
  permDrawer.value = true
}

async function savePermissions() {
  if (!permTarget.value) return
  permSaving.value = true
  try {
    await rbacApi.setRolePermissions(permTarget.value.id, checkedPerms.value)
    ElMessage.success('权限已更新（60 秒内生效）')
    permDrawer.value = false
    await load()
  } catch {
    // 拦截器已提示
  } finally {
    permSaving.value = false
  }
}

function openUserRoles(row: AdminUserVO) {
  userTarget.value = row
  userRoleIds.value = [...row.roleIds]
  userDialog.value = true
}

async function saveUserRoles() {
  if (!userTarget.value) return
  userSaving.value = true
  try {
    await rbacApi.setUserRoles(userTarget.value.id, userRoleIds.value)
    ElMessage.success('角色已更新（60 秒内生效）')
    userDialog.value = false
    await load()
  } catch {
    // 拦截器已提示
  } finally {
    userSaving.value = false
  }
}

/** 内置角色（超管）不允许改权限，提示一下而不是直接报错 */
function canEditPerm(row: RoleVO) {
  return row.code !== 'SUPER_ADMIN'
}

onMounted(load)
</script>

<template>
  <div class="page" v-loading="loading">
    <el-tabs v-model="tab">
      <el-tab-pane label="角色" name="roles">
        <div class="toolbar">
          <el-button type="primary" @click="openRole()">新增角色</el-button>
          <div class="spacer" />
          <el-button @click="load">刷新</el-button>
        </div>

        <el-card shadow="never">
          <el-table :data="roles">
            <el-table-column prop="code" label="标识" width="180" />
            <el-table-column prop="name" label="名称" width="140" />
            <el-table-column prop="description" label="说明" min-width="220" />
            <el-table-column label="类型" width="100">
              <template #default="{ row }">
                <el-tag size="small" :type="row.builtIn === 1 ? 'warning' : 'info'">
                  {{ row.builtIn === 1 ? '内置' : '自定义' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="90">
              <template #default="{ row }">
                <el-tag size="small" :type="row.status === 1 ? 'success' : 'info'">
                  {{ row.status === 1 ? '启用' : '停用' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="230" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" :disabled="!canEditPerm(row)" @click="openPermissions(row)">
                  分配权限
                </el-button>
                <el-button link type="primary" @click="openRole(row)">编辑</el-button>
                <el-button link type="danger" :disabled="row.builtIn === 1" @click="removeRole(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-tab-pane>

      <el-tab-pane label="管理员授权" name="admins">
        <el-alert
          type="info"
          :closable="false"
          show-icon
          title="角色变更最多 60 秒生效（权限做了进程内缓存）；不能修改自己的角色，避免把最后一个超管锁死。"
          class="tip"
        />
        <el-card shadow="never">
          <el-table :data="admins">
            <el-table-column prop="username" label="账号" width="180" />
            <el-table-column prop="nickname" label="昵称" width="160" />
            <el-table-column label="角色" min-width="200">
              <template #default="{ row }">
                <span v-if="row.roleNames">{{ row.roleNames }}</span>
                <span v-else class="muted">未分配（无法进入后台）</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="140">
              <template #default="{ row }">
                <el-button
                  link
                  type="primary"
                  :disabled="row.id === auth.user.value?.userId"
                  @click="openUserRoles(row)"
                >
                  分配角色
                </el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="roleDialog" :title="roleForm.id ? '编辑角色' : '新增角色'" width="480px">
      <el-form :model="roleForm" label-width="90px">
        <el-form-item label="标识" required>
          <el-input v-model="roleForm.code" placeholder="大写字母/数字/下划线，如 OPERATOR" :disabled="roleForm.id > 0" />
        </el-form-item>
        <el-form-item label="名称" required>
          <el-input v-model="roleForm.name" maxlength="64" />
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="roleForm.description" type="textarea" :rows="2" maxlength="200" />
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="roleForm.status" :active-value="1" :inactive-value="0" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="roleDialog = false">取消</el-button>
        <el-button type="primary" :loading="roleSaving" @click="saveRole">保存</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="permDrawer" :title="`分配权限 · ${permTarget?.name ?? ''}`" size="480px">
      <el-checkbox-group v-model="checkedPerms" class="perm-groups">
        <div v-for="g in grouped" :key="g.group" class="perm-group">
          <div class="perm-group-title">{{ g.group }}</div>
          <el-checkbox v-for="p in g.items" :key="p.id" :value="p.id" :label="p.id" class="perm-item">
            {{ p.name }}<span class="code">{{ p.code }}</span>
          </el-checkbox>
        </div>
      </el-checkbox-group>
      <template #footer>
        <div class="drawer-footer">
          <el-button @click="permDrawer = false">取消</el-button>
          <el-button type="primary" :loading="permSaving" @click="savePermissions">保存</el-button>
        </div>
      </template>
    </el-drawer>

    <el-dialog v-model="userDialog" :title="`分配角色 · ${userTarget?.username ?? ''}`" width="460px">
      <el-checkbox-group v-model="userRoleIds">
        <el-checkbox v-for="r in roles" :key="r.id" :value="r.id" :label="r.id" class="user-role-item">
          {{ r.name }}<span class="code">{{ r.code }}</span>
        </el-checkbox>
      </el-checkbox-group>
      <template #footer>
        <el-button @click="userDialog = false">取消</el-button>
        <el-button type="primary" :loading="userSaving" @click="saveUserRoles">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.tip {
  margin-bottom: 12px;
}

.spacer {
  flex: 1;
}

.muted {
  color: #c0c4cc;
}

.perm-groups {
  display: block;
}

.perm-group {
  margin-bottom: 16px;
}

.perm-group-title {
  font-weight: 600;
  margin-bottom: 8px;
  color: #303133;
}

.perm-item {
  display: flex;
  width: 100%;
  margin-right: 0;
}

.code {
  margin-left: 8px;
  color: #a8abb2;
  font-size: 12px;
}

.drawer-footer {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
}

.user-role-item {
  display: flex;
  width: 100%;
  margin-right: 0;
}
</style>
