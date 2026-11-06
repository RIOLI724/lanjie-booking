import { createRouter, createWebHistory } from 'vue-router'
import { getAccessToken } from '@/api/http'
import Home from '@/pages/Home.vue'
import Login from '@/pages/Login.vue'
import Register from '@/pages/Register.vue'
import Book from '@/pages/Book.vue'
import Orders from '@/pages/Orders.vue'
import Wallet from '@/pages/Wallet.vue'
import Coach from '@/pages/Coach.vue'
import Pickup from '@/pages/Pickup.vue'
import Me from '@/pages/Me.vue'

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'home', component: Home, meta: { requiresAuth: true } },
    { path: '/login', name: 'login', component: Login },
    { path: '/register', name: 'register', component: Register },
    { path: '/book', name: 'book', component: Book, meta: { requiresAuth: true } },
    { path: '/orders', name: 'orders', component: Orders, meta: { requiresAuth: true } },
    { path: '/wallet', name: 'wallet', component: Wallet, meta: { requiresAuth: true } },
    { path: '/coach', name: 'coach', component: Coach, meta: { requiresAuth: true } },
    { path: '/pickup', name: 'pickup', component: Pickup, meta: { requiresAuth: true } },
    { path: '/me', name: 'me', component: Me, meta: { requiresAuth: true } },
  ],
})

router.beforeEach((to) => {
  if (to.meta.requiresAuth && !getAccessToken()) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
})
