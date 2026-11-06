<script setup lang="ts">
</script>

<template>
  <nav class="dock" aria-label="手机导航">
    <router-link to="/" exact-active-class="active"><i></i><span>首页</span></router-link>
    <router-link to="/coach" active-class="active"><i></i><span>教练</span></router-link>
    <router-link to="/pickup" active-class="active"><i></i><span>拼场</span></router-link>
    <router-link to="/me" active-class="active"><i></i><span>我的</span></router-link>
  </nav>
</template>
